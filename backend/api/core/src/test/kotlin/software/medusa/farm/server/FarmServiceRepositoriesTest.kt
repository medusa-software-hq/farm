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
import software.medusa.farm.shared.FetchedIssue
import software.medusa.farm.shared.FetchedRepo
import software.medusa.farm.shared.InMemoryIssueStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.InMemoryRepoStore
import software.medusa.farm.shared.InMemorySessionStore
import software.medusa.farm.v1.GetSessionRequest
import software.medusa.farm.v1.ListIssuesRequest
import software.medusa.farm.v1.ListLinkedOrgsRequest
import software.medusa.farm.v1.ListRepositoriesRequest
import software.medusa.farm.v1.ListSessionsRequest
import software.medusa.farm.v1.SyncRepositoriesRequest

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
    override suspend fun start(installationId: Long) =
        error("the read path must not start a workflow")
  }

  // Records whether the manual sweep was triggered, so the SyncRepositories path can assert on it.
  private class RecordingSyncAllStarter : SyncAllStarter {
    var started = 0

    override suspend fun start() {
      started++
    }
  }

  private val clock = MutableClock(Instant.parse("2020-01-01T00:00:00Z"))
  private val linkedOrgs = InMemoryLinkedOrgStore()
  private val repos = InMemoryRepoStore(clock)
  private val issues = InMemoryIssueStore(clock)
  private val sessions = InMemorySessionStore(clock)
  private val syncAll = RecordingSyncAllStarter()

  private val service =
      FarmServiceImpl(
          linkedOrgs,
          repos,
          issues,
          sessions,
          GitHubOrgService(UnusedAppApiClient, linkedOrgs, UnusedRepoSyncStarter),
          syncAll,
      )

  // Prod-safe boot: ListRepositories serves an empty result with nothing linked yet.
  @Test
  fun `ListRepositories is empty when nothing is linked`() = runBlocking {
    assertEquals(
        0,
        service.listRepositories(ListRepositoriesRequest.getDefaultInstance()).repositoriesCount,
    )
  }

  // The manual "sync now" trigger starts the sweep.
  @Test
  fun `SyncRepositories starts the sweep`() = runBlocking {
    service.syncRepositories(SyncRepositoriesRequest.getDefaultInstance())
    assertEquals(1, syncAll.started)
  }

  // ListIssues serves the synced issues of every linked org, tagged with their repo's full name.
  @Test
  fun `ListIssues reports active issues across linked orgs`() = runBlocking {
    linkedOrgs.link(100L, "acme")
    issues.reconcile(
        installationId = 100L,
        githubRepoId = 1L,
        repoFullName = "acme/one",
        fetched = listOf(FetchedIssue(7, "Fix the thing", false), FetchedIssue(9, "Docs", false)),
        syncStartedAt = clock.current,
    )

    val response = service.listIssues(ListIssuesRequest.getDefaultInstance())

    assertEquals(
        setOf("acme/one" to 7, "acme/one" to 9),
        response.issuesList.map { it.repoFullName to it.number }.toSet(),
    )
  }

  // An issue carries its latest processing session's state; one with no session reads as empty.
  @Test
  fun `ListIssues tags issues with their session state`() = runBlocking {
    linkedOrgs.link(100L, "acme")
    issues.reconcile(
        installationId = 100L,
        githubRepoId = 1L,
        repoFullName = "acme/one",
        fetched = listOf(FetchedIssue(7, "Processed", false), FetchedIssue(9, "Untouched", false)),
        syncStartedAt = clock.current,
    )
    sessions.create(
        id = "s1",
        installationId = 100L,
        githubRepoId = 1L,
        number = 7,
        repoFullName = "acme/one",
        title = "Processed",
    )
    sessions.complete("s1")

    val response = service.listIssues(ListIssuesRequest.getDefaultInstance())
    val stateByNumber = response.issuesList.associate { it.number to it.sessionState }

    assertEquals("COMPLETED", stateByNumber[7])
    assertEquals("", stateByNumber[9])
  }

  // ListSessions serves the agent sessions across linked orgs, newest first, self-describing.
  @Test
  fun `ListSessions reports sessions across linked orgs`() = runBlocking {
    linkedOrgs.link(100L, "acme")
    sessions.create("s1", 100L, 1L, 7, "acme/one", "First")
    clock.current = clock.current.plusSeconds(60)
    sessions.create("s2", 100L, 1L, 9, "acme/one", "Second")
    sessions.complete("s2")

    val response = service.listSessions(ListSessionsRequest.getDefaultInstance())

    // Newest first.
    assertEquals(listOf("s2", "s1"), response.sessionsList.map { it.id })
    val completed = response.sessionsList.single { it.id == "s2" }
    assertEquals("COMPLETED", completed.state)
    assertEquals("acme/one", completed.repoFullName)
    assertEquals(9, completed.number)
  }

  // GetSession returns one session by id.
  @Test
  fun `GetSession returns the session`() = runBlocking {
    sessions.create("s1", 100L, 1L, 7, "acme/one", "Only")

    val response = service.getSession(GetSessionRequest.newBuilder().setId("s1").build())

    assertEquals("s1", response.session.id)
    assertEquals("RUNNING", response.session.state)
    assertEquals("", response.session.pullRequestUrl)
  }

  @Test
  fun `GetSession surfaces the recorded pull request`() = runBlocking {
    sessions.create("s1", 100L, 1L, 7, "acme/one", "Only")
    sessions.recordPullRequest("s1", 12, "https://github.com/acme/one/pull/12", "abc123")
    sessions.complete("s1")

    val response = service.getSession(GetSessionRequest.newBuilder().setId("s1").build())

    assertEquals("COMPLETED", response.session.state)
    assertEquals("https://github.com/acme/one/pull/12", response.session.pullRequestUrl)
  }

  // The link state surfaces on its own — an org reads as linked before any repo has synced.
  @Test
  fun `ListLinkedOrgs reports linked orgs with no repos synced`() = runBlocking {
    linkedOrgs.link(100L, "acme")
    linkedOrgs.link(200L, "beta")

    val response = service.listLinkedOrgs(ListLinkedOrgsRequest.getDefaultInstance())

    assertEquals(
        setOf("acme" to 100L, "beta" to 200L),
        response.orgsList.map { it.orgLogin to it.installationId }.toSet(),
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
