package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GhProperInstallationApiClientTest {
  private fun clientAgainst(server: FakeGitHubServer): GhInstallationApiClient =
      GhProperInstallationApiClient.build(
          FixedTokenProvider("ghs_secret"),
          baseUrl = server.baseUrl,
      )

  @Test
  fun `lists installation repositories across pages`() = runBlocking {
    FakeGitHubServer { request ->
          val page = pageOf(request.pathAndQuery)
          val repos =
              when (page) {
                1 -> """{"full_name": "medusa/one"}, {"full_name": "medusa/two"}"""
                else -> """{"full_name": "medusa/three"}"""
              }
          FakeGitHubServer.Response(200, """{"total_count": 3, "repositories": [$repos]}""")
        }
        .use { server ->
          val repositories = clientAgainst(server).listInstallationRepositories()
          assertEquals(
              listOf(
                  GhRepoFullName("medusa/one"),
                  GhRepoFullName("medusa/two"),
                  GhRepoFullName("medusa/three"),
              ),
              repositories,
          )
          // A page-1 and a page-2 request; the loop stops once total_count is reached.
          assertEquals(2, server.requests.size)
          assertTrue(server.requests.all { it.authorization == "Bearer ghs_secret" })
        }
  }

  @Test
  fun `serves the delegated resource surface over the same token`() = runBlocking {
    FakeGitHubServer { FakeGitHubServer.Response(200, """[{"number": 3, "title": "Hi"}]""") }
        .use { server ->
          val issues = clientAgainst(server).listIssues(GhRepoFullName("medusa/one"))
          assertEquals(listOf(GhIssue(3, "Hi")), issues)
          assertEquals("Bearer ghs_secret", server.requests.single().authorization)
        }
  }

  private fun pageOf(pathAndQuery: String): Int =
      Regex("[?&]page=(\\d+)").find(pathAndQuery)?.groupValues?.get(1)?.toInt() ?: 1
}
