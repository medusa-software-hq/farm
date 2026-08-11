package software.medusa.farm.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.FakeGitHub
import software.medusa.farm.github.FakeGitHubServer
import software.medusa.farm.github.GhCachingInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhIssue
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.github.TestAppKey
import software.medusa.farm.shared.InMemoryLinkedOrgStore

class MintingGitHubAppTest {
  private val appKey = TestAppKey()

  private fun appAgainst(
      server: FakeGitHubServer,
      store: InMemoryLinkedOrgStore,
  ): MintingGitHubApp {
    val appApiClient =
        GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl)
    return MintingGitHubApp(
        appApiClient,
        GhCachingInstallationApiClientProvider(
            GhProperInstallationApiClientProvider(appApiClient, baseUrl = server.baseUrl)
        ),
        store,
    )
  }

  @Test
  fun `reuses the cached installation client across calls instead of re-minting`() = runBlocking {
    val store = InMemoryLinkedOrgStore()
    store.link(100L, "acme")

    val fake =
        FakeGitHub(
            installationIdsByOrg = mapOf(GhOrgLogin("acme") to GhInstallationId(100L)),
            reposByInstallation =
                mapOf(GhInstallationId(100L) to listOf(GhRepoFullName("acme/one"))),
        )
    FakeGitHubServer(fake.handler).use { server ->
      val app = appAgainst(server, store)

      app.listAllRepositories()
      app.listAllRepositories()

      // Two steady-state reads, but the token was minted exactly once — evidence the cache is used.
      assertEquals(1, server.requests.count { it.pathAndQuery.endsWith("/access_tokens") })
    }
  }

  @Test
  fun `aggregates repositories across linked orgs, each carrying recent issues`() = runBlocking {
    val store = InMemoryLinkedOrgStore()
    store.link(100L, "acme")
    store.link(200L, "beta")

    val fake =
        FakeGitHub(
            installationIdsByOrg =
                mapOf(
                    GhOrgLogin("acme") to GhInstallationId(100L),
                    GhOrgLogin("beta") to GhInstallationId(200L),
                ),
            reposByInstallation =
                mapOf(
                    GhInstallationId(100L) to
                        listOf(GhRepoFullName("acme/one"), GhRepoFullName("acme/two")),
                    GhInstallationId(200L) to listOf(GhRepoFullName("beta/api")),
                ),
            issuesByRepo =
                mapOf(
                    GhRepoFullName("acme/one") to listOf(GhIssue(1, "First"), GhIssue(2, "Second"))
                ),
        )
    FakeGitHubServer(fake.handler).use { server ->
      val repositories = appAgainst(server, store).listAllRepositories()

      assertEquals(
          setOf("acme/one", "acme/two", "beta/api"),
          repositories.map { it.fullName.value }.toSet(),
      )
      val acmeOne = repositories.single { it.fullName == GhRepoFullName("acme/one") }
      assertEquals(listOf(GhIssue(1, "First"), GhIssue(2, "Second")), acmeOne.recentIssues)
      val betaApi = repositories.single { it.fullName == GhRepoFullName("beta/api") }
      assertEquals(GhOrgLogin("beta"), betaApi.orgLogin)
      assertEquals(emptyList(), betaApi.recentIssues)
    }
  }
}
