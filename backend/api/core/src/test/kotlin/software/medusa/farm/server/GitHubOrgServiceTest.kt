package software.medusa.farm.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.FakeGitHub
import software.medusa.farm.github.FakeGitHubServer
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.TestAppKey
import software.medusa.farm.shared.InMemoryLinkedOrgStore

class GitHubOrgServiceTest {
  private val appKey = TestAppKey()

  private class RecordingRepoSyncStarter : RepoSyncStarter {
    val started = mutableListOf<Long>()

    override fun start(installationId: Long) {
      started += installationId
    }
  }

  @Test
  fun `linkOrg resolves the installation, stores the org, and triggers a sync`() = runBlocking {
    val store = InMemoryLinkedOrgStore()
    val starter = RecordingRepoSyncStarter()
    val fake =
        FakeGitHub(
            installationIdsByOrg = mapOf(GhOrgLogin("acme") to GhInstallationId(100L)),
            reposByInstallation = emptyMap(),
        )
    FakeGitHubServer(fake.handler).use { server ->
      val appApiClient =
          GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl)
      val service = GitHubOrgService(appApiClient, store, starter)

      val installationId = service.linkOrg(GhOrgLogin("acme"))

      assertEquals(GhInstallationId(100L), installationId)
      assertEquals(listOf(100L), store.list().map { it.installationId })
      assertEquals(listOf(100L), starter.started)
      // The installation is resolved exactly once, at link time.
      assertEquals(1, server.requests.count { it.pathAndQuery.endsWith("/installation") })
    }
  }
}
