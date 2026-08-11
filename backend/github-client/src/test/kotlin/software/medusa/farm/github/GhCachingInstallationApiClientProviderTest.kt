package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

class GhCachingInstallationApiClientProviderTest {
  private val appKey = TestAppKey()

  @Test
  fun `reuses one client per installation, so its token is minted once`() = runBlocking {
    val repo = GhRepoFullName("acme/one")
    val fake =
        FakeGitHub(
            installationIdsByOrg = mapOf(GhOrgLogin("acme") to GhInstallationId(100L)),
            reposByInstallation = mapOf(GhInstallationId(100L) to listOf(repo)),
        )
    FakeGitHubServer(fake.handler).use { server ->
      val appClient =
          GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl)
      val provider =
          GhCachingInstallationApiClientProvider(
              GhProperInstallationApiClientProvider(appClient, baseUrl = server.baseUrl)
          )

      val first = provider.provideForInstallation(GhInstallationId(100L))
      val second = provider.provideForInstallation(GhInstallationId(100L))
      assertSame(first, second)

      first.listInstallationRepositories()
      second.listInstallationRepositories()

      // One shared client, so the token is minted exactly once and no id is resolved.
      assertEquals(0, server.requests.count { it.pathAndQuery.endsWith("/installation") })
      assertEquals(1, server.requests.count { it.pathAndQuery.endsWith("/access_tokens") })
    }
  }
}
