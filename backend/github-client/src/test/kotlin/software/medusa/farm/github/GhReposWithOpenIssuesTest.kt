package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Drives the org-wide fetch: what it takes in, what it leaves out, and what it costs. */
class GhReposWithOpenIssuesTest {
  private val installation = GhInstallationId(100L)

  // FakeGitHub attributes a call to an installation by the token it minted for it.
  private fun clientAgainst(server: FakeGitHubServer): GhInstallationApiClient =
      GhProperInstallationApiClient.build(
          FixedTokenProvider("tok-${installation.value}"),
          baseUrl = server.baseUrl,
      )

  @Test
  fun `reads every repo's open issues in one query`(): Unit = runBlocking {
    val fake =
        FakeGitHub(
            installationIdsByOrg = mapOf(GhOrgLogin("acme") to installation),
            reposByInstallation =
                mapOf(
                    installation to listOf(GhRepoFullName("acme/one"), GhRepoFullName("acme/two"))
                ),
            issuesByRepo =
                mapOf(GhRepoFullName("acme/one") to listOf(GhIssue(5, "Ready", listOf("go")))),
        )

    FakeGitHubServer(fake.handler).use { server ->
      val fetched = clientAgainst(server).listReposWithOpenIssues()

      assertEquals(listOf("acme/one", "acme/two"), fetched.map { it.repo.fullName.value })
      assertEquals(listOf(GhIssue(5, "Ready", listOf("go"))), fetched.first().openIssues)
      // A repo with nothing open says so, rather than being left out of the answer.
      assertEquals(emptyList(), fetched.last().openIssues)
      // Two repos, one query: what the fetch costs does not follow how many repos the org has.
      assertEquals(1, server.requests.count { it.pathAndQuery == "/graphql" })
    }
  }

  @Test
  fun `leaves out a repo the org has and the installation cannot reach`(): Unit = runBlocking {
    val fake =
        FakeGitHub(
            installationIdsByOrg = mapOf(GhOrgLogin("acme") to installation),
            reposByInstallation =
                mapOf(
                    installation to listOf(GhRepoFullName("acme/mine")),
                    GhInstallationId(200L) to listOf(GhRepoFullName("acme/theirs")),
                ),
        )

    FakeGitHubServer(fake.handler).use { server ->
      val fetched = clientAgainst(server).listReposWithOpenIssues()

      // The org offers both; only the one this installation was given may be acted on, and a sync
      // that adopted the other would be claiming a repo nobody here can touch.
      assertEquals(listOf("acme/mine"), fetched.map { it.repo.fullName.value })
    }
  }

  @Test
  fun `follows the repository cursor past the first page`(): Unit = runBlocking {
    FakeGitHubServer { request ->
          when {
            isRepositoriesListing(request) ->
                FakeGitHubServer.Response(200, repositoriesListing("acme/one", "acme/two"))
            resumesFrom(request, "repo-cursor") ->
                FakeGitHubServer.Response(200, orgPage(hasNextPage = false, repoNode("acme/two")))
            else ->
                FakeGitHubServer.Response(
                    200,
                    orgPage(hasNextPage = true, repoNode("acme/one"), endCursor = "repo-cursor"),
                )
          }
        }
        .use { server ->
          val fetched = clientAgainst(server).listReposWithOpenIssues()

          assertEquals(listOf("acme/one", "acme/two"), fetched.map { it.repo.fullName.value })
        }
  }

  @Test
  fun `follows the issue cursor of a repo with more issues than a page holds`(): Unit =
      runBlocking {
        val firstPage =
            issuesConnection(hasNextPage = true, endCursor = "issue-cursor", numbers = listOf(1))
        val secondPage = issuesConnection(hasNextPage = false, numbers = listOf(2))

        FakeGitHubServer { request ->
              when {
                isRepositoriesListing(request) ->
                    FakeGitHubServer.Response(200, repositoriesListing("acme/one"))
                resumesFrom(request, "issue-cursor") ->
                    FakeGitHubServer.Response(200, repositoryIssuesPage(secondPage))
                else ->
                    FakeGitHubServer.Response(
                        200,
                        orgPage(hasNextPage = false, repoNode("acme/one", firstPage)),
                    )
              }
            }
            .use { server ->
              val fetched = clientAgainst(server).listReposWithOpenIssues()

              // Both pages, in one repo's issue list: a page is where the answer continues, not
              // where it stops.
              assertEquals(listOf(1, 2), fetched.single().openIssues.map { it.number })
            }
      }

  @Test
  fun `a query GitHub answered with errors fails rather than returning half of it`(): Unit =
      runBlocking {
        FakeGitHubServer { request ->
              if (isRepositoriesListing(request)) {
                FakeGitHubServer.Response(200, repositoriesListing("acme/one"))
              } else {
                // GraphQL reports a query it could not resolve inside the body of a 200.
                FakeGitHubServer.Response(
                    200,
                    """{"data": null, "errors": [{"message": "Could not resolve to an Org"}]}""",
                )
              }
            }
            .use { server ->
              val failure =
                  assertFailsWith<IllegalStateException> {
                    clientAgainst(server).listReposWithOpenIssues()
                  }

              assertTrue(failure.message!!.contains("Could not resolve to an Org"))
            }
      }

  private fun isRepositoriesListing(request: FakeGitHubServer.Request): Boolean =
      request.pathAndQuery.substringBefore('?') == "/installation/repositories"

  /** Whether this is the query asking for what came after [cursor] — the only place it appears. */
  private fun resumesFrom(request: FakeGitHubServer.Request, cursor: String): Boolean =
      """"$cursor"""" in request.body

  private fun repositoriesListing(vararg fullNames: String): String {
    val repos =
        fullNames.joinToString(",") {
          """{"id": ${repoId(it)}, "full_name": "$it", "name": "${it.substringAfter('/')}", """ +
              """"private": false, "default_branch": "main"}"""
        }
    return """{"total_count": ${fullNames.size}, "repositories": [$repos]}"""
  }

  private fun orgPage(
      hasNextPage: Boolean,
      vararg nodes: String,
      endCursor: String? = null,
  ): String =
      """{"data": {"organization": {"repositories": """ +
          """{${pageInfo(hasNextPage, endCursor)}, "nodes": [${nodes.joinToString(",")}]}}}}"""

  private fun repositoryIssuesPage(issues: String): String =
      """{"data": {"repository": {"issues": $issues}}}"""

  private fun repoNode(
      fullName: String,
      issues: String = issuesConnection(hasNextPage = false, numbers = emptyList()),
  ): String =
      """{"databaseId": ${repoId(fullName)}, "name": "${fullName.substringAfter('/')}", """ +
          """"issues": $issues}"""

  private fun issuesConnection(
      hasNextPage: Boolean,
      numbers: List<Int>,
      endCursor: String? = null,
  ): String {
    val nodes =
        numbers.joinToString(",") {
          """{"number": $it, "title": "Issue $it", "labels": {"nodes": []}}"""
        }
    return """{${pageInfo(hasNextPage, endCursor)}, "nodes": [$nodes]}"""
  }

  private fun pageInfo(hasNextPage: Boolean, endCursor: String?): String {
    val cursor = if (endCursor == null) "null" else "\"$endCursor\""
    return """"pageInfo": {"hasNextPage": $hasNextPage, "endCursor": $cursor}"""
  }

  /** The listing and the org query have to agree on a repo's id for it to be matched at all. */
  private fun repoId(fullName: String): Long = fullName.hashCode().toLong() and 0x7fffffff
}
