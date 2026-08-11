package software.medusa.farm.github

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class GhRefreshingInstallationTokenProviderTest {
  private val appKey = TestAppKey()

  private fun appClientAgainst(server: FakeGitHubServer): GhAppApiClient =
      GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl)

  @Test
  fun `mints once across two resource calls through the installation client`() = runBlocking {
    val repo = GhRepoFullName("acme/one")
    val fake =
        FakeGitHub(
            installationIdsByOrg = mapOf(GhOrgLogin("acme") to GhInstallationId(100L)),
            reposByInstallation = mapOf(GhInstallationId(100L) to listOf(repo)),
            issuesByRepo = mapOf(repo to listOf(GhIssue(1, "hi"))),
        )
    FakeGitHubServer(fake.handler).use { server ->
      val tokenProvider =
          GhRefreshingInstallationTokenProvider(appClientAgainst(server), GhOrgLogin("acme"))
      val client = GhProperInstallationApiClient.build(tokenProvider, baseUrl = server.baseUrl)

      client.listInstallationRepositories()
      client.listIssues(repo)

      // The id is resolved once and the token minted once, then reused for the second call.
      assertEquals(1, server.requests.count { it.pathAndQuery.endsWith("/installation") })
      assertEquals(1, server.requests.count { it.pathAndQuery.endsWith("/access_tokens") })
    }
  }

  @Test
  fun `resolves once and re-mints only past the refresh margin`() = runBlocking {
    val base = Instant.parse("2026-08-11T00:00:00Z")
    val resolves = AtomicInteger(0)
    val mints = AtomicInteger(0)

    FakeGitHubServer { request ->
          when {
            request.pathAndQuery.endsWith("/installation") -> {
              resolves.incrementAndGet()
              FakeGitHubServer.Response(200, """{"id": 7}""")
            }
            request.pathAndQuery.endsWith("/access_tokens") -> {
              val n = mints.incrementAndGet()
              // Each token lives an hour longer, so a later `now` re-crosses the margin.
              val expiresAt = base.plus(Duration.ofHours(n.toLong()))
              FakeGitHubServer.Response(
                  201,
                  """{"token": "token-$n", "expires_at": "$expiresAt"}""",
              )
            }
            else -> FakeGitHubServer.Response(404, "unexpected ${request.pathAndQuery}")
          }
        }
        .use { server ->
          var current = base
          val provider =
              GhRefreshingInstallationTokenProvider(
                  appClientAgainst(server),
                  GhOrgLogin("acme"),
                  refreshMargin = Duration.ofMinutes(5),
                  now = { current },
              )

          assertEquals("token-1", provider.provideToken())
          assertEquals(1, mints.get())

          // Still before (expiry - margin): the cache holds, and the id is not re-resolved.
          current = base.plus(Duration.ofMinutes(10))
          assertEquals("token-1", provider.provideToken())
          assertEquals(1, mints.get())

          // Past (expiry - margin): re-mint, but still no second installation lookup.
          current = base.plus(Duration.ofMinutes(56))
          assertEquals("token-2", provider.provideToken())
          assertEquals(2, mints.get())

          assertEquals(1, resolves.get())
        }
  }
}
