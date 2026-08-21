package software.medusa.farm.github

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GhProperInstallationApiClientTest {
  private fun clientAgainst(server: FakeGitHubServer): GhInstallationApiClient =
      GhProperInstallationApiClient.build(
          FixedTokenProvider("ghs_secret"),
          baseUrl = server.baseUrl,
      )

  @Test
  fun `follows the Link header across pages and stops when next is absent`() = runBlocking {
    FakeGitHubServer { request ->
          // Page 1 advertises a next page via the Link header; page 2 does not, so the walk stops.
          if (isSecondPage(request.pathAndQuery)) {
            FakeGitHubServer.Response(200, page(repoJson(3, "medusa/three")))
          } else {
            FakeGitHubServer.Response(
                200,
                page(repoJson(1, "medusa/one"), repoJson(2, "medusa/two")),
                mapOf("Link" to "</installation/repositories?per_page=100&page=2>; rel=\"next\""),
            )
          }
        }
        .use { server ->
          val repositories = clientAgainst(server).listInstallationRepositories()
          assertEquals(
              listOf(
                  GhRepoFullName("medusa/one"),
                  GhRepoFullName("medusa/two"),
                  GhRepoFullName("medusa/three"),
              ),
              repositories.map { it.fullName },
          )
          assertEquals(listOf(1L, 2L, 3L), repositories.map { it.id.value })
          val one = repositories.first()
          assertEquals("one", one.name)
          assertEquals(true, one.isPrivate)
          assertEquals("trunk", one.defaultBranch)
          // Exactly the two pages: the first plus the one the Link header pointed to.
          assertEquals(2, server.requests.size)
          assertTrue(server.requests.all { it.authorization == "Bearer ghs_secret" })
        }
  }

  @Test
  fun `a mid-stream page error aborts the whole listing`() = runBlocking {
    FakeGitHubServer { request ->
          if (isSecondPage(request.pathAndQuery)) {
            FakeGitHubServer.Response(500, "boom")
          } else {
            FakeGitHubServer.Response(
                200,
                page(repoJson(1, "medusa/one")),
                mapOf("Link" to "</installation/repositories?per_page=100&page=2>; rel=\"next\""),
            )
          }
        }
        .use { server ->
          // Complete-or-fail: the failing second page propagates, never a truncated list.
          val failure =
              assertFailsWith<IllegalStateException> {
                clientAgainst(server).listInstallationRepositories()
              }
          assertTrue(failure.message!!.contains("500"))
        }
  }

  @Test
  fun `serves the delegated resource surface over the same token`() = runBlocking {
    FakeGitHubServer { FakeGitHubServer.Response(200, """[{"number": 3, "title": "Hi"}]""") }
        .use { server ->
          val issues = clientAgainst(server).listIssues(GhRepoFullName("medusa/one"))
          assertEquals(listOf(GhIssue(3, "Hi", emptyList())), issues)
          assertEquals("Bearer ghs_secret", server.requests.single().authorization)
        }
  }

  @Test
  fun `opens a pull request and maps the response`() = runBlocking {
    FakeGitHubServer {
          FakeGitHubServer.Response(
              201,
              """{"number": 42, "html_url": "https://github.com/acme/one/pull/42", """ +
                  """"state": "open", "head": {"sha": "abc123"}, "base": {"ref": "trunk/v1"}}""",
          )
        }
        .use { server ->
          val pr =
              clientAgainst(server)
                  .createPullRequest(
                      GhRepoFullName("acme/one"),
                      head = "farm/issue-7",
                      base = "trunk",
                      title = "Fix it",
                      body = "Refs #7",
                  )
          assertEquals(42, pr.number)
          assertEquals("https://github.com/acme/one/pull/42", pr.url)
          assertEquals(GhPullRequestState.OPEN, pr.state)
          assertEquals("abc123", pr.headSha)
          assertEquals("trunk/v1", pr.baseBranch)

          val request = server.requests.single()
          assertEquals("POST", request.method)
          assertTrue(request.pathAndQuery.endsWith("/repos/acme/one/pulls"))
        }
  }

  @Test
  fun `takes a label off an issue, encoding the name into the path`() = runBlocking {
    FakeGitHubServer { FakeGitHubServer.Response(200, "[]") }
        .use { server ->
          clientAgainst(server).removeLabel(GhRepoFullName("acme/one"), 7, "farm:ready")

          val request = server.requests.single()
          assertEquals("DELETE", request.method)
          assertEquals("/repos/acme/one/issues/7/labels/farm%3Aready", request.pathAndQuery)
        }
  }

  @Test
  fun `taking off a label the issue has not got is not a failure`(): Unit = runBlocking {
    FakeGitHubServer { FakeGitHubServer.Response(404, """{"message": "Label does not exist"}""") }
        .use { server ->
          // Asking twice has to be as good as asking once: what the caller wants is the label gone.
          clientAgainst(server).removeLabel(GhRepoFullName("acme/one"), 7, "farm:ready")
        }
  }

  @Test
  fun `reads pull request state, distinguishing merged from closed-unmerged`() = runBlocking {
    FakeGitHubServer { request ->
          val head = """"head": {"sha": "s"}, "base": {"ref": "trunk"}"""
          when {
            request.pathAndQuery.endsWith("/pulls/1") ->
                FakeGitHubServer.Response(
                    200,
                    """{"number": 1, "html_url": "u", "state": "closed", "merged": true, $head}""",
                )
            request.pathAndQuery.endsWith("/pulls/2") ->
                FakeGitHubServer.Response(
                    200,
                    """{"number": 2, "html_url": "u", "state": "closed", "merged": false, $head}""",
                )
            else ->
                FakeGitHubServer.Response(
                    200,
                    """{"number": 3, "html_url": "u", "state": "open", $head}""",
                )
          }
        }
        .use { server ->
          val client = clientAgainst(server)
          assertEquals(
              GhPullRequestState.MERGED,
              client.getPullRequest(GhRepoFullName("acme/one"), 1).state,
          )
          assertEquals(
              GhPullRequestState.CLOSED,
              client.getPullRequest(GhRepoFullName("acme/one"), 2).state,
          )
          assertEquals(
              GhPullRequestState.OPEN,
              client.getPullRequest(GhRepoFullName("acme/one"), 3).state,
          )
        }
  }

  @Test
  fun `reads a review's verdict and what it said`() = runBlocking {
    // Shaped after a real review: the state, the box the reviewer typed into, and a state this
    // library has no name for.
    FakeGitHubServer { _ ->
          FakeGitHubServer.Response(
              200,
              """[{"id": 4976223985, "state": "COMMENTED", "body": "General review comment",
                   "submitted_at": "2026-08-19T20:00:11Z"},
                  {"id": 4976223986, "state": "CHANGES_REQUESTED", "body": "",
                   "submitted_at": "2026-08-19T20:05:00Z"},
                  {"id": 4976223987, "state": "SOMETHING_NEW", "submitted_at": null}]""",
          )
        }
        .use { server ->
          val reviews = clientAgainst(server).listReviews(GhRepoFullName("acme/one"), 57)

          assertEquals(
              listOf(
                  GhPullRequestReviewState.COMMENTED,
                  GhPullRequestReviewState.CHANGES_REQUESTED,
                  GhPullRequestReviewState.UNRECOGNIZED,
              ),
              reviews.map { it.state },
          )
          assertEquals("General review comment", reviews.first().body)
          assertEquals(Instant.parse("2026-08-19T20:00:11Z"), reviews.first().submittedAt)
          // A review with no box filled in is not a review with no content — it has line comments.
          assertEquals("", reviews[1].body)
        }
  }

  @Test
  fun `reads the comments a review left on lines, and which review left them`() = runBlocking {
    FakeGitHubServer { _ ->
          FakeGitHubServer.Response(
              200,
              """[{"id": 3816291987, "pull_request_review_id": 4976223985,
                   "path": "src/main/java/com/example/Greeter.java", "line": 5,
                   "body": "Very nice line"},
                  {"id": 3816292548, "pull_request_review_id": 4976223985,
                   "path": "src/main/java/com/example/GreetingCheck.java", "line": null,
                   "body": "Uh, bad line"}]""",
          )
        }
        .use { server ->
          val comments = clientAgainst(server).listReviewComments(GhRepoFullName("acme/one"), 57)

          assertEquals(listOf(4976223985L, 4976223985L), comments.map { it.reviewId })
          assertEquals("src/main/java/com/example/Greeter.java", comments.first().path)
          assertEquals(5, comments.first().line)
          assertEquals("Very nice line", comments.first().body)
          // A comment whose line has since moved out of the diff keeps its file but loses its line.
          assertNull(comments[1].line)
        }
  }

  @Test
  fun `reads how far a check got, how it came out, and what it reported`() = runBlocking {
    // Shaped after a real commit's checks: one still going, one red with a report, one green, and
    // a conclusion this library has no name for.
    FakeGitHubServer { _ ->
          FakeGitHubServer.Response(
              200,
              """{"total_count": 4, "check_runs": [
                   {"id": 51226077961, "name": "test", "status": "in_progress",
                    "conclusion": null},
                   {"id": 51226077962, "name": "build", "status": "completed",
                    "conclusion": "failure",
                    "output": {"title": "1 error", "summary": "Compilation failed",
                               "text": null}},
                   {"id": 51226077963, "name": "lint", "status": "completed",
                    "conclusion": "success", "output": {"title": null, "summary": null}},
                   {"id": 51226077964, "name": "deploy", "status": "completed",
                    "conclusion": "something_new"}]}""",
          )
        }
        .use { server ->
          val checkRuns = clientAgainst(server).listCheckRuns(GhRepoFullName("acme/one"), "abc123")

          assertEquals(
              listOf(
                  GhCheckRunStatus.IN_PROGRESS,
                  GhCheckRunStatus.COMPLETED,
                  GhCheckRunStatus.COMPLETED,
                  GhCheckRunStatus.COMPLETED,
              ),
              checkRuns.map { it.status },
          )
          assertEquals(
              listOf(
                  null,
                  GhCheckRunConclusion.FAILURE,
                  GhCheckRunConclusion.SUCCESS,
                  GhCheckRunConclusion.UNRECOGNIZED,
              ),
              checkRuns.map { it.conclusion },
          )
          assertEquals(GhCheckRunId(51226077962), checkRuns[1].id)
          assertEquals(
              GhCheckRunOutput(title = "1 error", summary = "Compilation failed", text = ""),
              checkRuns[1].output,
          )
          // A check is free to report nothing and be read for its conclusion alone.
          assertEquals(GhCheckRunOutput(title = "", summary = "", text = ""), checkRuns[3].output)
        }
  }

  @Test
  fun `reads the places a check pointed at`() = runBlocking {
    FakeGitHubServer { _ ->
          FakeGitHubServer.Response(
              200,
              """[{"path": "src/main/kotlin/A.kt", "start_line": 5, "end_line": 5,
                   "annotation_level": "failure", "message": "Unresolved reference: foo"},
                  {"path": ".github", "start_line": 0, "end_line": 0,
                   "annotation_level": "failure", "message": "Process completed with exit code 1"}]""",
          )
        }
        .use { server ->
          val annotations =
              clientAgainst(server)
                  .listCheckRunAnnotations(GhRepoFullName("acme/one"), GhCheckRunId(51226077962))

          assertEquals("src/main/kotlin/A.kt", annotations.first().path)
          assertEquals(5, annotations.first().startLine)
          assertEquals("Unresolved reference: foo", annotations.first().message)
          // A check with nothing in the diff to point at says so with line zero.
          assertNull(annotations[1].startLine)
        }
  }

  @Test
  fun `reads the checks a branch requires, past the rules that are about something else`() =
      runBlocking {
        FakeGitHubServer { _ ->
              FakeGitHubServer.Response(
                  200,
                  """[{"type": "deletion", "ruleset_id": 5},
                      {"type": "pull_request", "ruleset_id": 5,
                       "parameters": {"allowed_merge_methods": ["merge"]}},
                      {"type": "required_status_checks", "ruleset_id": 5,
                       "parameters": {"strict_required_status_checks_policy": true,
                         "required_status_checks": [
                           {"context": "Backend (implementation) / Check", "integration_id": 15368},
                           {"context": "schema / Integration test", "integration_id": 15368}]}}]""",
              )
            }
            .use { server ->
              val required =
                  clientAgainst(server)
                      .listRequiredCheckNames(GhRepoFullName("acme/one"), "trunk/v1")

              assertEquals(
                  listOf("Backend (implementation) / Check", "schema / Integration test"),
                  required,
              )
              // A branch name is free to contain a slash, which is not a path separator here.
              assertTrue(
                  server.requests
                      .single()
                      .pathAndQuery
                      .startsWith("/repos/acme/one/rules/branches/trunk%2Fv1")
              )
            }
      }

  @Test
  fun `a branch under no rules requires no check`() = runBlocking {
    FakeGitHubServer { _ -> FakeGitHubServer.Response(200, "[]") }
        .use { server ->
          assertEquals(
              emptyList(),
              clientAgainst(server).listRequiredCheckNames(GhRepoFullName("acme/one"), "trunk"),
          )
        }
  }

  private fun isSecondPage(pathAndQuery: String): Boolean =
      Regex("[?&]page=(\\d+)").find(pathAndQuery)?.groupValues?.get(1)?.toInt() == 2

  private fun page(vararg repos: String): String =
      """{"total_count": 3, "repositories": [${repos.joinToString(", ")}]}"""

  private fun repoJson(id: Long, fullName: String): String =
      """{"id": $id, "full_name": "$fullName", "name": "${fullName.substringAfter('/')}", """ +
          """"private": true, "default_branch": "trunk"}"""
}
