package software.medusa.farm.server

import io.grpc.StatusRuntimeException
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhAppPermissionSet
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.MintedGhInstallationToken
import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction
import software.medusa.farm.shared.AgentWarning
import software.medusa.farm.shared.InMemoryIssueStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.InMemoryRepoStore
import software.medusa.farm.shared.InMemorySessionStore
import software.medusa.farm.v1.AgentRunEntry
import software.medusa.farm.v1.GetSessionRunsRequest

/** Covers the read path a run's timeline is drawn from — every try at it, crashed ones included. */
class FarmServiceRunsTest {
  private object UnusedAppApiClient : GhAppApiClient {
    override suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId =
        error("the read path must not call GitHub")

    override suspend fun mintInstallationToken(
        installationId: GhInstallationId
    ): MintedGhInstallationToken = error("the read path must not call GitHub")

    override suspend fun fetchDeclaredPermissions(): GhAppPermissionSet =
        error("the read path must not call GitHub")
  }

  private object UnusedRepoSyncStarter : RepoSyncStarter {
    override suspend fun start(installationId: Long) =
        error("the read path must not start a workflow")
  }

  private object UnusedSyncAllStarter : SyncAllStarter {
    override suspend fun start() = error("the read path must not start a workflow")
  }

  private val clock = Clock.systemUTC()
  private val linkedOrgs = InMemoryLinkedOrgStore()
  private val sessions = InMemorySessionStore(clock)

  private val service =
      FarmServiceImpl(
          linkedOrgs,
          InMemoryRepoStore(clock),
          InMemoryIssueStore(clock),
          sessions,
          GitHubOrgService(UnusedAppApiClient, linkedOrgs, UnusedRepoSyncStarter),
          UnusedSyncAllStarter,
      )

  @Test
  fun `an unknown session is a miss, and one with no runs yet is not`(): Unit = runBlocking {
    assertFailsWith<StatusRuntimeException> { service.getSessionRuns(request("nobody")) }

    openSession()
    assertEquals(0, service.getSessionRuns(request("s")).runsCount)
  }

  @Test
  fun `a try that crashed is served alongside the one that replaced it`(): Unit = runBlocking {
    openSession()
    sessions.startRunAttempt("s", ordinal = 0, attempt = 1)
    sessions.appendRunEntry("s", 0, attempt = 1, position = 0, entry = AgentWarning("careful"))

    // Try 1 never closes. Try 2 does the work and finishes.
    sessions.startRunAttempt("s", ordinal = 0, attempt = 2)
    sessions.appendRunEntry(
        "s",
        0,
        attempt = 2,
        position = 0,
        entry =
            AgentStep(
                text = "fixed it",
                toolActions = listOf(AgentToolAction.EditFile("src/A.kt")),
            ),
    )
    sessions.finishRunAttempt(
        "s",
        ordinal = 0,
        attempt = 2,
        outcome = AgentRunOutcome.SUCCEEDED,
        cost = AgentRunCost(usd = 0.12, turns = 2, durationMs = 345),
        summary = "a summary",
    )

    val run = service.getSessionRuns(request("s")).runsList.single()
    assertEquals(0, run.ordinal)
    assertEquals(listOf(1, 2), run.attemptsList.map { it.number })

    val crashed = run.attemptsList.first()
    assertEquals("ABANDONED", crashed.state)
    assertFalse(crashed.hasOutcome(), "a try that never closed cannot say how it went")
    assertEquals("careful", crashed.entriesList.single().warning)

    val finished = run.attemptsList.last()
    assertEquals("FINISHED", finished.state)
    assertEquals("SUCCEEDED", finished.outcome.outcome)
    assertEquals("a summary", finished.outcome.summary)
    assertEquals(0.12, finished.outcome.cost.usd)

    val step = finished.entriesList.single().step
    assertEquals("fixed it", step.text)
    assertEquals("src/A.kt", step.toolActionsList.single().editedPath)
  }

  @Test
  fun `a warning and a step stay apart on the wire`(): Unit = runBlocking {
    openSession()
    sessions.startRunAttempt("s", ordinal = 0, attempt = 1)
    sessions.appendRunEntry("s", 0, 1, position = 0, entry = AgentWarning("careful"))
    sessions.appendRunEntry("s", 0, 1, position = 1, entry = AgentStep("did it", emptyList()))

    val entries = service.getSessionRuns(request("s")).runsList.single().attemptsList.single()
    // Which of the two an entry is has to survive the crossing, or a timeline cannot tell them
    // apart to draw them differently.
    assertEquals(
        listOf(AgentRunEntry.EntryCase.WARNING, AgentRunEntry.EntryCase.STEP),
        entries.entriesList.map { it.entryCase },
    )
    assertTrue(entries.state == "RUNNING")
  }

  private fun request(sessionId: String) =
      GetSessionRunsRequest.newBuilder().setSessionId(sessionId).build()

  private suspend fun openSession() =
      sessions.create(
          id = "s",
          installationId = 1L,
          githubRepoId = 2L,
          number = 3,
          repoFullName = "acme/one",
          title = "Fix it",
      )
}
