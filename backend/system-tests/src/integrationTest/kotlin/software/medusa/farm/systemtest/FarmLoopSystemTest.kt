package software.medusa.farm.systemtest

import io.grpc.ManagedChannelBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhInstallationApiClient
import software.medusa.farm.github.GhMergeMethod
import software.medusa.farm.github.GhNewReviewComment
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.github.GhReviewVerdict
import software.medusa.farm.v1.AgentRunEntry
import software.medusa.farm.v1.AgentStep
import software.medusa.farm.v1.AgentToolAction
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.GetSessionRunsRequest
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.ListSessionsRequest
import software.medusa.farm.v1.SessionRunAttempt
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
  private val startedAt = TimeSource.Monotonic.markNow()

  // Kept across the waits rather than within one: the run worked before a review is still there to
  // be read afterwards, and what has been reported once does not want reporting again.
  private val reportedCountByAttempt = mutableMapOf<Pair<Int, Int>, Int>()
  private val reportedStateByAttempt = mutableMapOf<Pair<Int, Int>, String>()

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

    // What notices a labelled issue is the sweep, which the deployment runs on a schedule and this
    // asks for directly — on every look rather than once. Linking the org above started a sweep of
    // its own, and a "sync now" that lands while one is running attaches to it rather than starting
    // another; that one began before this issue was filed and can finish without ever seeing it.
    // The first ask after it finishes is the sweep that finds the issue.
    //
    // Waited for before the pull request rather than after it: the farm opens the session as it
    // picks the issue up, which is what lets the wait below say what the agent is doing as it
    // works.
    val sessionId =
        awaitUntil("a session for the issue", SETTLE_LIMIT) {
          api.syncRepositories(SyncRepositoriesRequest.getDefaultInstance())
          api.listSessions(ListSessionsRequest.getDefaultInstance())
              .sessionsList
              .firstOrNull { it.number == issue.number }
              ?.id
        }

    val pullRequest =
        reportingAgentSteps(api, sessionId) {
          awaitUntil("a pull request for the issue", AGENT_RUN_LIMIT) {
            gitHub.listOpenPullRequests(repo).firstOrNull()
          }
        }

    val firstRun = runsOf(api, sessionId).single()
    assertEquals(0, firstRun.ordinal, "the first run is the initial attempt")
    assertTrue(
        firstRun.attemptsList.single().entriesCount > 0,
        "the run recorded nothing the agent did",
    )

    // Waited for rather than read straight off: the pull request reaches GitHub a moment before
    // Farm records the wait against the session.
    awaitUntil("the session to say it is waiting on a review", SETTLE_LIMIT) {
      stateOf(api, sessionId).takeIf { it == "AWAITING_REVIEW" }
    }

    // Reviewed the way a person does: something in the box, and something against a line the agent
    // actually wrote.
    val touched =
        gitHub.listPullRequestFiles(repo, pullRequest.number).firstNotNullOfOrNull { file ->
          file.findFirstAddedLine()?.let { file.path to it }
        } ?: error("the pull request adds no line to comment on")
    gitHub.createReview(
        repo = repo,
        number = pullRequest.number,
        verdict = GhReviewVerdict.REQUEST_CHANGES,
        body = "Let's go with `Howdy there` instead — same again, keep the check passing.",
        comments =
            listOf(
                GhNewReviewComment(
                    path = touched.first,
                    line = touched.second,
                    body = "This is the line I mean.",
                )
            ),
    )

    // A fixup is a push to the same branch, so the pull request moving off the commit it was opened
    // at is what says the review was worked.
    reportingAgentSteps(api, sessionId) {
      awaitUntil("the fixup to reach the pull request", AGENT_RUN_LIMIT) {
        gitHub.listOpenPullRequests(repo).firstOrNull {
          it.number == pullRequest.number && it.headSha != pullRequest.headSha
        }
      }
    }

    assertEquals(
        listOf(0, 1),
        runsOf(api, sessionId).map { it.ordinal },
        "the fixup was not recorded as a run of its own",
    )

    // The fixup landed a moment ago, and GitHub refuses a merge while it is still working out
    // whether the pull request can be merged — which it is doing because of that push. Asking
    // again is what a person would do; if it never stops refusing, its own refusal is reported.
    retrying(MERGE_ATTEMPTS) {
      gitHub.mergePullRequest(repo, pullRequest.number, GhMergeMethod.SQUASH)
    }

    // Over, rather than merely not RUNNING: a session awaiting review is not running either, and
    // this wait would end on it the moment the fixup went up.
    val finished =
        awaitUntil("the session to finish", SETTLE_LIMIT) {
          api.listSessions(ListSessionsRequest.getDefaultInstance()).sessionsList.firstOrNull {
            it.id == sessionId && it.finishedAtMillis > 0
          }
        }
    assertEquals("COMPLETED", finished.state)

    // The sweep's own view of the queue: open issues carrying the label. An issue still in it once
    // the session is over is picked up by the next sweep to look, and the change that just landed
    // is implemented all over again.
    //
    // Read straight after the session rather than waited for: the label comes off before the
    // session closes.
    assertTrue(
        gitHub.listIssues(repo).none { READY_LABEL in it.labels },
        "the finished issue is still offered up for work",
    )
  }

  /** What the session says it is, as somebody reading the farm's own screens would see it. */
  private suspend fun stateOf(
      api: FarmServiceGrpcKt.FarmServiceCoroutineStub,
      sessionId: String,
  ): String? =
      api.listSessions(ListSessionsRequest.getDefaultInstance())
          .sessionsList
          .firstOrNull { it.id == sessionId }
          ?.state

  private suspend fun runsOf(api: FarmServiceGrpcKt.FarmServiceCoroutineStub, sessionId: String) =
      api.getSessionRuns(GetSessionRunsRequest.newBuilder().setSessionId(sessionId).build())
          .runsList

  /**
   * Runs [action] until it stops throwing, [times] at most. The last failure is thrown rather than
   * a failure of this method's own, so what GitHub said is what gets read.
   */
  private suspend fun retrying(times: Int, action: suspend () -> Unit) {
    repeat(times - 1) { attempt ->
      try {
        return action()
      } catch (refused: IllegalStateException) {
        say("refused (${attempt + 1} of $times): ${refused.message?.take(SAID_LIMIT)}")
        delay(POLL_INTERVAL)
      }
    }

    action()
  }

  /**
   * Waits for [probe] to have an answer, saying what it was waiting for when it runs out. Real work
   * takes real time here: nothing is skipped and nothing is stubbed.
   */
  private suspend fun <ResultT> awaitUntil(
      what: String,
      limit: Duration,
      probe: suspend () -> ResultT?,
  ): ResultT {
    say("waiting for $what")
    val startedWaiting = TimeSource.Monotonic.markNow()
    while (startedWaiting.elapsedNow() < limit) {
      probe()?.let {
        say("got $what")
        return it
      }
      delay(POLL_INTERVAL)
    }

    error("gave up after $limit waiting for $what")
  }

  /**
   * Prints what the agent records for as long as [block] runs.
   *
   * Two agent runs are most of what this test spends, and neither says anything from outside while
   * it happens: the farm's own log is a step of its own that runs once this one is over, and what
   * the agent did reaches the log the farm keeps long before it reaches GitHub. Whoever is watching
   * gets to see the work rather than a quarter of an hour of nothing.
   */
  private suspend fun <ResultT> reportingAgentSteps(
      api: FarmServiceGrpcKt.FarmServiceCoroutineStub,
      sessionId: String,
      block: suspend () -> ResultT,
  ): ResultT = coroutineScope {
    val reporter = launch {
      while (isActive) {
        runsOf(api, sessionId).forEach { run ->
          run.attemptsList.forEach { attempt ->
            val attemptKey = run.ordinal to attempt.number
            val what = "run ${run.ordinal}, try ${attempt.number}"

            attempt.entriesList.drop(reportedCountByAttempt[attemptKey] ?: 0).forEach {
              say("$what: ${describe(it)}")
            }
            reportedCountByAttempt[attemptKey] = attempt.entriesCount

            // An attempt that ends says why in its outcome, which is the one place a run that
            // failed for a reason of its own rather than the agent's says so.
            if (reportedStateByAttempt.put(attemptKey, attempt.state) != attempt.state) {
              say("$what: ${attempt.state}${describeOutcome(attempt)}")
            }
          }
        }
        delay(POLL_INTERVAL)
      }
    }

    try {
      block()
    } finally {
      reporter.cancel()
    }
  }

  private fun describeOutcome(attempt: SessionRunAttempt): String =
      if (!attempt.hasOutcome()) ""
      else " — ${attempt.outcome.outcome}, ${attempt.outcome.summary.take(SAID_LIMIT)}"

  private fun describe(entry: AgentRunEntry): String =
      when (entry.entryCase) {
        AgentRunEntry.EntryCase.STEP -> describe(entry.step)
        AgentRunEntry.EntryCase.WARNING -> "warning: ${entry.warning}"
        else -> "an entry of no kind at all"
      }

  private fun describe(step: AgentStep): String {
    val said = step.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(SAID_LIMIT)
    val did = step.toolActionsList.joinToString(", ") { describe(it) }
    return listOf(said, did).filter { it.isNotEmpty() }.joinToString(" ")
  }

  private fun describe(action: AgentToolAction): String =
      when (action.actionCase) {
        AgentToolAction.ActionCase.EDITED_PATH -> "[edits ${action.editedPath}]"
        AgentToolAction.ActionCase.READ_PATH -> "[reads ${action.readPath}]"
        AgentToolAction.ActionCase.COMMAND -> "[runs ${action.command.take(SAID_LIMIT)}]"
        AgentToolAction.ActionCase.QUERY -> "[searches ${action.query.take(SAID_LIMIT)}]"
        AgentToolAction.ActionCase.OTHER_TOOL -> "[${action.otherTool}]"
        else -> "[a tool action of no kind at all]"
      }

  /** Stamped with how far into the test it is, which is what says where the time went. */
  private fun say(message: String) {
    val elapsed = startedAt.elapsedNow()
    println("[%2dm%02ds] %s".format(elapsed.inWholeMinutes, elapsed.inWholeSeconds % 60, message))
  }

  private companion object {
    const val READY_LABEL = "farm:ready"

    // An agent cloning a repository, working, and pushing. Generous: a slow run is not a failure.
    val AGENT_RUN_LIMIT = 15.minutes

    // Bookkeeping either side of the agent — the sweep noticing, the gate seeing a merge. The
    // gate's own poll is a minute, so this has to be comfortably more than that.
    val SETTLE_LIMIT = 5.minutes

    val POLL_INTERVAL = 5.seconds

    // Enough of a step to follow what the agent is doing, without wrapping the log it is printed
    // to.
    const val SAID_LIMIT = 120

    // Enough for GitHub to work out that a just-pushed branch can be merged.
    const val MERGE_ATTEMPTS = 5
  }
}
