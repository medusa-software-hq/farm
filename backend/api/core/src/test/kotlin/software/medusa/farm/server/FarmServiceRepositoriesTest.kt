package software.medusa.farm.server

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.MintedGhInstallationToken
import software.medusa.farm.shared.FetchedRepo
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.InMemoryRepoStore
import software.medusa.farm.v1.ListRepositoriesRequest

/** Covers the read path, which serves the synced repos table with no live GitHub call. */
class FarmServiceRepositoriesTest {
  // A clock the test advances so the store's soft-orphan watermark is meaningful.
  private class MutableClock(var current: Instant) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
  }

  // The read path never touches GitHub; this stands in for the App client and fails if it is
  // called.
  private object UnusedAppApiClient : GhAppApiClient {
    override suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId =
        error("the read path must not call GitHub")

    override suspend fun mintInstallationToken(
        installationId: GhInstallationId
    ): MintedGhInstallationToken = error("the read path must not call GitHub")
  }

  // The read path never starts a workflow; this stands in for the starter, unused here.
  private object UnusedRepoSyncStarter : RepoSyncStarter {
    override fun start(installationId: Long) = error("the read path must not start a workflow")
  }

  private val clock = MutableClock(Instant.parse("2020-01-01T00:00:00Z"))
  private val linkedOrgs = InMemoryLinkedOrgStore()
  private val repos = InMemoryRepoStore(clock)

  private val service =
      FarmServiceImpl(
          linkedOrgs,
          repos,
          GitHubOrgService(UnusedAppApiClient, linkedOrgs, UnusedRepoSyncStarter),
      )

  // Prod-safe boot: ListRepositories serves an empty result with nothing linked yet.
  @Test
  fun `ListRepositories is empty when nothing is linked`() = runBlocking {
    assertEquals(
        0,
        service.listRepositories(ListRepositoriesRequest.getDefaultInstance()).repositoriesCount,
    )
  }

  // The read path is the synced table, tagged with each installation's org, active repos only.
  @Test
  fun `ListRepositories reports active repos across linked orgs`() = runBlocking {
    linkedOrgs.link(100L, "acme")
    linkedOrgs.link(200L, "beta")
    repos.reconcile(100L, listOf(fetched(1, "acme/one"), fetched(2, "acme/two")), clock.current)
    repos.reconcile(200L, listOf(fetched(3, "beta/api")), clock.current)

    val response = service.listRepositories(ListRepositoriesRequest.getDefaultInstance())

    assertEquals(
        setOf("acme/one", "acme/two", "beta/api"),
        response.repositoriesList.map { it.fullName }.toSet(),
    )
    assertEquals(
        "beta",
        response.repositoriesList.single { it.fullName == "beta/api" }.orgLogin,
    )
  }

  @Test
  fun `ListRepositories excludes orphaned repos`() = runBlocking {
    linkedOrgs.link(100L, "acme")
    repos.reconcile(100L, listOf(fetched(1, "acme/one"), fetched(2, "acme/gone")), clock.current)

    clock.current = clock.current.plusSeconds(60)
    // A later sync that no longer sees `acme/gone` orphans it.
    repos.reconcile(100L, listOf(fetched(1, "acme/one")), clock.current)

    val response = service.listRepositories(ListRepositoriesRequest.getDefaultInstance())

    assertEquals(listOf("acme/one"), response.repositoriesList.map { it.fullName })
  }

  private fun fetched(id: Long, fullName: String) =
      FetchedRepo(
          githubRepoId = id,
          fullName = fullName,
          name = fullName.substringAfter('/'),
          isPrivate = false,
          defaultBranch = "main",
      )
}
