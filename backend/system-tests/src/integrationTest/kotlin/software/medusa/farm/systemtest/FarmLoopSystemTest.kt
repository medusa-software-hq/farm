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
import software.medusa.farm.github.GhInstallationApiClient
import software.medusa.farm.github.GhMergeMethod
import software.medusa.farm.github.GhNewReviewComment
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.github.GhReviewVerdict
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.GetSessionRunsRequest
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.ListSessionsRequest
import software.medusa.farm.v1.SyncRepositoriesRequest

/**
 * Drives one issue all the way round: filed, worked, reviewed, worked again, merged.
 *
 * Everything under it is real — a database, a GitHub org, the agent — so this is the only test that
 * can say the loop works rather than that its pieces do. It costs money and minutes, and it is what
 * the ephemeral farm exists to be run against.
 *
 * What it knows of the farm is where its API answers. A farm started beside it and one deployed
 * somewhere are the same thing from here, which is what lets these cover both.
 *
 * The repository it drives is made for the run and dropped after it, so the tree the agent starts
 * from is the same every time — a shared one would move forward with every merge and never be the
 * same twice.
 */
class FarmLoopSystemTest {
  @Test
  fun `an issue is worked, reviewed, worked again, and merged`(): Unit = runBlocking {
    val config = SystemTestConfig.fromEnvironment()

    val appApiClient =
        GhProperAppApiClient.build(
            config.fixtureManagerApp.clientId,
            config.fixtureManagerApp.privateKey,
        )
    val gitHub =
        GhProperInstallationApiClientProvider(appApiClient)
            .provideForInstallation(appApiClient.resolveInstallationId(GhOrgLogin(config.orgLogin)))

    dropWhatEarlierRunsLeft(gitHub, config)

    // Made here and dropped below, so the tree the agent starts from is the same every time and the
    // test can be run anywhere rather than only by something that made a repository for it.
    val repo =
        gitHub
            .createRepositoryFromTemplate(
                template = GhRepoFullName(config.templateFullName),
                owner = config.orgLogin,
                name = config.repoName,
                isPrivate = true,
            )
            .fullName

    try {
      driveTheLoop(config, gitHub, repo)
    } finally {
      // The fast path. A run that dies outright leaves this undone, which is what the sweep above
      // is for — an `always()` step would not have survived that either.
      gitHub.deleteRepository(repo)
    }
  }

  /**
   * Drops repositories an earlier run made and never got to drop. Runs are serialised, so anything
   * named for a run other than this one belongs to nobody — and a leftover still holds a labelled
   * issue, which the next farm to sweep would pick up and work as its own.
   */
  private suspend fun dropWhatEarlierRunsLeft(
      gitHub: GhInstallationApiClient,
      config: SystemTestConfig,
  ) {
    gitHub
        .listInstallationRepositories()
        .filter { it.name.startsWith(SystemTestConfig.REPO_PREFIX) && it.name != config.repoName }
        .forEach { gitHub.deleteRepository(it.fullName) }
  }

  private suspend fun driveTheLoop(
      config: SystemTestConfig,
      gitHub: GhInstallationApiClient,
      repo: GhRepoFullName,
  ) {
    val api =
        FarmServiceGrpcKt.FarmServiceCoroutineStub(
            ManagedChannelBuilder.forAddress(config.apiHost, config.apiPort).usePlaintext().build()
        )

    // The farm only looks at orgs it has been linked to, and only at issues carrying its label —
    // which a repository made from a template has not got.
    api.linkOrg(LinkOrgRequest.newBuilder().setOrgLogin(config.orgLogin).build())
    gitHub.ensureLabel(repo, READY_LABEL)

    // The fixture's check spans two files, so this asks for a coordinated edit rather than a
    // one-line one: getting half of it right fails `verifyGreeting` instead of passing.
    val issue =
        gitHub.createIssue(
            repo = repo,
            title = "Change the greeting to Howdy",
            body =
                "The greeting should be `Howdy` rather than `Hello`. Make sure the project's own " +
                    "check still passes afterwards.",
            labels = listOf(READY_LABEL),
        )

    // The sweep is what notices a labelled issue; the deployment runs it on a schedule and this
    // asks for it directly.
    api.syncRepositories(SyncRepositoriesRequest.getDefaultInstance())

    val pullRequest =
        awaitUntil("a pull request for the issue", AGENT_RUN_LIMIT) {
          gitHub.listOpenPullRequests(repo).firstOrNull()
        }

    val sessionId =
        awaitUntil("a session for the issue", SETTLE_LIMIT) {
          api.listSessions(ListSessionsRequest.getDefaultInstance())
              .sessionsList
              .firstOrNull { it.number == issue.number }
              ?.id
        }

    val firstRun = runsOf(api, sessionId).single()
    assertEquals(0, firstRun.ordinal, "the first run is the initial attempt")
    assertTrue(
        firstRun.attemptsList.single().entriesCount > 0,
        "the run recorded nothing the agent did",
    )

    // Reviewed the way a person does: something in the box, and something against a file the agent
    // actually touched.
    val touched = gitHub.listPullRequestPaths(repo, pullRequest.number).first()
    gitHub.createReview(
        repo = repo,
        number = pullRequest.number,
        verdict = GhReviewVerdict.REQUEST_CHANGES,
        body = "Let's go with `Howdy there` instead — same again, keep the check passing.",
        comments = listOf(GhNewReviewComment(path = touched, body = "This is the file I mean.")),
    )

    // A fixup is a push to the same branch, so the pull request moving off the commit it was opened
    // at is what says the review was worked.
    awaitUntil("the fixup to reach the pull request", AGENT_RUN_LIMIT) {
      gitHub.listOpenPullRequests(repo).firstOrNull {
        it.number == pullRequest.number && it.headSha != pullRequest.headSha
      }
    }

    assertEquals(
        listOf(0, 1),
        runsOf(api, sessionId).map { it.ordinal },
        "the fixup was not recorded as a run of its own",
    )

    gitHub.mergePullRequest(repo, pullRequest.number, GhMergeMethod.SQUASH)

    val finished =
        awaitUntil("the session to finish", SETTLE_LIMIT) {
          api.listSessions(ListSessionsRequest.getDefaultInstance()).sessionsList.firstOrNull {
            it.id == sessionId && it.state != "RUNNING"
          }
        }
    assertEquals("COMPLETED", finished.state)
  }

  private suspend fun runsOf(api: FarmServiceGrpcKt.FarmServiceCoroutineStub, sessionId: String) =
      api.getSessionRuns(GetSessionRunsRequest.newBuilder().setSessionId(sessionId).build())
          .runsList

  /**
   * Waits for [probe] to have an answer, saying what it was waiting for when it runs out. Real work
   * takes real time here: nothing is skipped and nothing is stubbed.
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
    const val READY_LABEL = "farm:ready"

    // An agent cloning a repository, working, and pushing. Generous: a slow run is not a failure.
    val AGENT_RUN_LIMIT = 15.minutes

    // Bookkeeping either side of the agent — the sweep noticing, the gate seeing a merge. The
    // gate's own poll is a minute, so this has to be comfortably more than that.
    val SETTLE_LIMIT = 5.minutes

    val POLL_INTERVAL = 5.seconds
  }
}
