package software.medusa.farm.server

import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import software.medusa.farm.shared.FetchedRepo
import software.medusa.farm.shared.InMemoryFibonacciStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.InMemoryRepoStore
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.ListFibonacciRequest
import software.medusa.farm.v1.ListRepositoriesRequest

class FarmServiceLinkOrgTest {
  // A clock the test advances so the store's soft-orphan watermark is meaningful.
  private class MutableClock(var current: Instant) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
  }

  private val clock = MutableClock(Instant.parse("2020-01-01T00:00:00Z"))
  private val linkedOrgs = InMemoryLinkedOrgStore()
  private val repos = InMemoryRepoStore(clock)

  private val service =
      FarmServiceImpl(
          InMemoryFibonacciStore(),
          NoOpFibonacciStarter,
          linkedOrgs,
          repos,
          gitHubOrgs = null,
      )

  @Test
  fun `without a configured app, LinkOrg is UNIMPLEMENTED`() = runBlocking {
    val failure =
        assertFailsWith<StatusRuntimeException> {
          service.linkOrg(LinkOrgRequest.newBuilder().setOrgLogin("acme").build())
        }
    assertEquals(Status.Code.UNIMPLEMENTED, failure.status.code)
  }

  @Test
  fun `an unconfigured app leaves the other RPCs working`() = runBlocking {
    assertEquals(
        0,
        service.listFibonacci(ListFibonacciRequest.getDefaultInstance()).numbersCount,
    )
  }

  // Prod-safe boot: ListRepositories serves an empty result with no repos and GitHub unconfigured.
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
