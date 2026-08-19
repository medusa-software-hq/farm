package software.medusa.farm.systemtest

import io.grpc.ManagedChannelBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.GetSessionRunsRequest
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.ListSessionsRequest
import software.medusa.farm.v1.SyncRepositoriesRequest

/**
 * Drives one issue all the way round: filed, worked, reviewed, worked again, merged.
 *
 * Everything under it is real — a database branch, a GitHub org, the agent — so this is the only
 * test that can say the loop works rather than that its pieces do. It costs money and minutes, and
 * it is the reason the ephemeral module exists.
 *
 * The repository it drives is made for this run and dropped after it, so the tree it starts from is
 * the same every time; a shared one would move forward with each merge and never be the same twice.
 *
 * What it knows of the farm is where its API answers. A farm started here and one deployed
 * somewhere are the same thing from here, which is what would let this cover both.
 */
class FarmLoopSystemTest {
  @Test
  fun `an issue is worked, reviewed, worked again, and merged`(): Unit = runBlocking {
    val config = SystemTestConfig.fromEnvironment()

    // Built here rather than handed over by the farm: acting as the reviewer is the test's own
    // business, and would be the same against a farm it had not started.
    val gitHub = GhProperAppApiClient.build(config.gitHubApp.clientId, config.gitHubApp.pem)

    EphemeralFarm.run(config) { farm ->
      runBlocking {
        val installation = gitHub.resolveInstallationId(GhOrgLogin(config.orgLogin))
        val fixture =
            FixtureGitHub(
                repoFullName = config.repoFullName,
                token = gitHub.mintInstallationToken(installation).token,
            )
        val api =
            FarmServiceGrpcKt.FarmServiceCoroutineStub(
                ManagedChannelBuilder.forAddress(farm.apiHost, farm.apiPort).usePlaintext().build()
            )

        // Farm only looks at repositories of orgs it has been linked to, and only at issues
        // carrying its label — which a repository made from a template does not have.
        api.linkOrg(LinkOrgRequest.newBuilder().setOrgLogin(config.orgLogin).build())
        fixture.ensureLabel(FARM_READY_LABEL)
        // The fixture's check spans two files, so this asks for a coordinated edit rather than a
        // one-line one: getting half of it right fails `verifyGreeting` instead of passing.
        val issueNumber =
            fixture.createIssue(
                title = "Change the greeting to Howdy",
                body =
                    "The greeting should be `Howdy` rather than `Hello`. Make sure the project's " +
                        "own check still passes afterwards.",
                label = FARM_READY_LABEL,
            )

        // The sweep is what notices a labelled issue; the deployment runs it on a schedule and
        // this asks for it directly.
        api.syncRepositories(SyncRepositoriesRequest.getDefaultInstance())

        val (pullRequestNumber, firstSha) =
            awaitUntil("a pull request for the issue", AGENT_RUN_LIMIT) {
              fixture.openPullRequests().firstOrNull()
            }

        val sessionId =
            awaitUntil("a session for the issue", SETTLE_LIMIT) {
              api.listSessions(ListSessionsRequest.getDefaultInstance())
                  .sessionsList
                  .firstOrNull { it.number == issueNumber }
                  ?.id
            }

        val firstRun = runsOf(api, sessionId).single()
        assertEquals(0, firstRun.ordinal, "the first run is the initial attempt")
        assertTrue(
            firstRun.attemptsList.single().entriesCount > 0,
            "the run recorded nothing the agent did",
        )

        // Reviewed the way a person does: something in the box, something against a file the agent
        // actually touched.
        val touched = fixture.changedPaths(pullRequestNumber).first()
        fixture.requestChanges(
            pullRequestNumber = pullRequestNumber,
            body = "Let's go with `Howdy there` instead — same again, keep the check passing.",
            path = touched,
            comment = "This is the file I mean.",
        )

        // A fixup is a push to the same branch, so the pull request moving off the commit it was
        // opened at is what says the review was worked.
        awaitUntil("the fixup to reach the pull request", AGENT_RUN_LIMIT) {
          fixture.openPullRequests().firstOrNull {
            it.first == pullRequestNumber && it.second != firstSha
          }
        }

        val runs = runsOf(api, sessionId)
        assertEquals(listOf(0, 1), runs.map { it.ordinal }, "the fixup was not recorded as a run")

        fixture.merge(pullRequestNumber)

        val merged =
            awaitUntil("the session to finish", SETTLE_LIMIT) {
              api.listSessions(ListSessionsRequest.getDefaultInstance()).sessionsList.firstOrNull {
                it.id == sessionId && it.state != "RUNNING"
              }
            }
        // Success is the merge, not the session ending — so this is the assertion that matters.
        assertEquals("COMPLETED", merged.state)
      }
    }
  }

  private suspend fun runsOf(api: FarmServiceGrpcKt.FarmServiceCoroutineStub, sessionId: String) =
      api.getSessionRuns(GetSessionRunsRequest.newBuilder().setSessionId(sessionId).build())
          .runsList

  /**
   * Waits for [probe] to have an answer, saying what it was waiting for when it runs out. Real work
   * takes real time here: nothing is skipped and nothing is mocked.
   */
  private suspend fun <ResultT> awaitUntil(
      what: String,
      limit: Duration,
      probe: suspend () -> ResultT?,
  ): ResultT {
    val startedAt = TimeSource.Monotonic.markNow()
    while (startedAt.elapsedNow() < limit) {
      probe()?.let {
        return it
      }
      delay(POLL_INTERVAL)
    }

    error("gave up after $limit waiting for $what")
  }

  private companion object {
    const val FARM_READY_LABEL = "farm:ready"

    // An agent cloning a repository, working, and pushing. Generous: a slow run is not a failure.
    val AGENT_RUN_LIMIT = 15.minutes

    // Bookkeeping either side of the agent — the sweep noticing, the gate seeing a merge. The
    // gate's own poll is a minute, so this has to be comfortably more than that.
    val SETTLE_LIMIT = 5.minutes

    val POLL_INTERVAL = 5.seconds
  }
}
