package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                  """"state": "open", "head": {"sha": "abc123"}}""",
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

          val request = server.requests.single()
          assertEquals("POST", request.method)
          assertTrue(request.pathAndQuery.endsWith("/repos/acme/one/pulls"))
        }
  }

  @Test
  fun `reads pull request state, distinguishing merged from closed-unmerged`() = runBlocking {
    FakeGitHubServer { request ->
          val head = """"head": {"sha": "s"}"""
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

  private fun isSecondPage(pathAndQuery: String): Boolean =
      Regex("[?&]page=(\\d+)").find(pathAndQuery)?.groupValues?.get(1)?.toInt() == 2

  private fun page(vararg repos: String): String =
      """{"total_count": 3, "repositories": [${repos.joinToString(", ")}]}"""

  private fun repoJson(id: Long, fullName: String): String =
      """{"id": $id, "full_name": "$fullName", "name": "${fullName.substringAfter('/')}", """ +
          """"private": true, "default_branch": "trunk"}"""
}
