package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GhUniversalResourcesApiClientTest {
  private fun clientAgainst(
      server: FakeGitHubServer,
      token: String = "ghs_token",
  ): GhResourcesApiClient =
      GhUniversalResourcesApiClient(FixedTokenProvider(token), baseUrl = server.baseUrl)

  @Test
  fun `lists issues, presenting the provided token`() = runBlocking {
    FakeGitHubServer { request ->
          assertTrue(request.pathAndQuery.startsWith("/repos/acme/one/issues"))
          FakeGitHubServer.Response(
              200,
              """[{"number": 7, "title": "Fix the thing"}, {"number": 9, "title": "Docs"}]""",
          )
        }
        .use { server ->
          val issues = clientAgainst(server).listIssues(GhRepoFullName("acme/one"))
          assertEquals(
              listOf(GhIssue(7, "Fix the thing", emptyList()), GhIssue(9, "Docs", emptyList())),
              issues,
          )
          assertEquals("Bearer ghs_token", server.requests.single().authorization)
        }
  }

  @Test
  fun `drops pull requests, which GitHub returns from the issues endpoint`() = runBlocking {
    FakeGitHubServer {
          FakeGitHubServer.Response(
              200,
              """
              [
                {"number": 1, "title": "A real issue"},
                {"number": 2, "title": "A PR", "pull_request": {"url": "https://x"}}
              ]
              """
                  .trimIndent(),
          )
        }
        .use { server ->
          val issues = clientAgainst(server).listIssues(GhRepoFullName("acme/one"))
          assertEquals(listOf(GhIssue(1, "A real issue", emptyList())), issues)
        }
  }

  @Test
  fun `fetches an issue body, and treats a null body as empty`() = runBlocking {
    FakeGitHubServer { request ->
          when {
            request.pathAndQuery.endsWith("/issues/7") ->
                FakeGitHubServer.Response(200, """{"number": 7, "body": "Please fix the thing."}""")
            else -> FakeGitHubServer.Response(200, """{"number": 9}""")
          }
        }
        .use { server ->
          val client = clientAgainst(server)
          assertEquals("Please fix the thing.", client.getIssueBody(GhRepoFullName("acme/one"), 7))
          assertEquals("", client.getIssueBody(GhRepoFullName("acme/one"), 9))
        }
  }

  @Test
  fun `parses label names`() = runBlocking {
    FakeGitHubServer {
          FakeGitHubServer.Response(
              200,
              """[{"number": 5, "title": "Ready", "labels": [{"name": "farm:ready"}, {"name": "bug"}]}]""",
          )
        }
        .use { server ->
          val issues = clientAgainst(server).listIssues(GhRepoFullName("acme/one"))
          assertEquals(listOf(GhIssue(5, "Ready", listOf("farm:ready", "bug"))), issues)
        }
  }
}
