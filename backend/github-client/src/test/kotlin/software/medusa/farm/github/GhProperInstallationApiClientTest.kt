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
                1 -> "${repoJson(1, "medusa/one")}, ${repoJson(2, "medusa/two")}"
                else -> repoJson(3, "medusa/three")
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
              repositories.map { it.fullName },
          )
          assertEquals(listOf(1L, 2L, 3L), repositories.map { it.id.value })
          val one = repositories.first()
          assertEquals("one", one.name)
          assertEquals(true, one.isPrivate)
          assertEquals("trunk", one.defaultBranch)
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

  private fun repoJson(id: Long, fullName: String): String =
      """{"id": $id, "full_name": "$fullName", "name": "${fullName.substringAfter('/')}", """ +
          """"private": true, "default_branch": "trunk"}"""
}
