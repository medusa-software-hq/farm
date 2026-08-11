package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

class GhCachingInstallationApiClientProviderTest {
  private val appKey = TestAppKey()

  @Test
  fun `reuses one client per org, so an org resolves its installation once`() = runBlocking {
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

      val first = provider.provideForOrg(GhOrgLogin("acme"))
      val second = provider.provideForOrg(GhOrgLogin("acme"))
      assertSame(first, second)

      first.listInstallationRepositories()
      second.listInstallationRepositories()

      // One shared client, so the installation is resolved and the token minted exactly once.
      assertEquals(1, server.requests.count { it.pathAndQuery.endsWith("/installation") })
      assertEquals(1, server.requests.count { it.pathAndQuery.endsWith("/access_tokens") })
    }
  }
}
