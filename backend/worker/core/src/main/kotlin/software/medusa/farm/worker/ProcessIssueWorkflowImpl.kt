package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.github.GhPullRequestState
import software.medusa.farm.shared.FarmLabels
import software.medusa.farm.shared.ProcessIssueWorkflow

/**
 * The processing run: open a session, attempt the issue with the agent (clone → code → open a PR if
 * anything changed), follow the pull request to wherever it goes, and finish the issue — say how it
 * went and take it out of the queue. The session id is drawn from the workflow's deterministic RNG
 * so an activity retry reuses it rather than opening a second session.
 *
 * If the work fails (an activity exhausts its bounded retries), the session is marked FAILED and
 * the workflow itself fails — so a broken run is visible instead of sitting RUNNING forever.
 * Session creation is outside the guard: if even that fails there is no session to fail.
 */
class ProcessIssueWorkflowImpl : ProcessIssueWorkflow {
  private val activities =
      Workflow.newActivityStub(
          ProcessIssueActivities::class.java,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(1))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(MAX_ATTEMPTS).build())
              .build(),
      )

  // Finishing does not give up, where the work does: while this run is alive the sweep's start is
  // refused, so an issue GitHub will not let Farm unlabel costs one visibly stuck run — whereas a
  // run that ended with the label still on would be worked afresh, by a fresh agent opening a
  // fresh pull request, every sweep.
  private val finishActivities =
      Workflow.newActivityStub(
          ProcessIssueActivities::class.java,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(1))
              .setRetryOptions(
                  RetryOptions.newBuilder().setMaximumInterval(FINISH_RETRY_INTERVAL_CAP).build()
              )
              .build(),
      )

  // The attempt clones and runs a real coding agent, so it gets a long timeout and few retries
  // (each attempt is expensive, and a repeated failure is usually a real problem, not a blip).
  private val publishActivities =
      Workflow.newActivityStub(
          PublishActivities::class.java,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(45))
              .setRetryOptions(
                  RetryOptions.newBuilder().setMaximumAttempts(ATTEMPT_MAX_ATTEMPTS).build()
              )
              .build(),
      )

  // How far finishing the issue got. Both endings of the run finish it, so the second one has to
  // know what the first managed.
  private var outcomeReported = false
  private var leftQueue = false

  override fun process(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      number: Int,
      title: String,
  ) {
    val sessionId = Workflow.randomUUID().toString()
    activities.createSession(sessionId, installationId, githubRepoId, number, repoFullName, title)
    try {
      val attempt =
          publishActivities.attemptIssue(sessionId, installationId, repoFullName, number, title)
      val outcome =
          if (
              attempt.pullRequestUrl != null &&
                  attempt.pullRequestNumber != null &&
                  attempt.pullRequestHeadSha != null
          ) {
            activities.recordPullRequest(
                sessionId,
                attempt.pullRequestNumber,
                attempt.pullRequestUrl,
                attempt.pullRequestHeadSha,
            )
            activities.postIssueComment(
                installationId,
                repoFullName,
                number,
                openedComment(attempt.pullRequestUrl),
            )
            followPullRequest(
                sessionId,
                installationId,
                repoFullName,
                number,
                title,
                attempt.pullRequestNumber,
            )
          } else {
            IssueOutcome.NOTHING_TO_CHANGE
          }
      finishIssue(installationId, repoFullName, number, outcome)
      activities.completeSession(sessionId)
    } catch (e: ActivityFailure) {
      activities.failSession(sessionId)
      finishIssue(installationId, repoFullName, number, IssueOutcome.FAILED)
      throw e
    }
  }

  /**
   * Ends Farm's involvement with the issue: says how it went, then takes the label off. In that
   * order, so an issue never leaves the queue without something on it saying why — the label is the
   * only thing the sweep reads, and one dropped in silence takes the issue out of sight of both the
   * sweep and whoever filed it.
   *
   * On every ending, not only a merge. Anything left labelled is worked again by a later sweep, and
   * the run before it would be reimplemented from scratch.
   */
  private fun finishIssue(
      installationId: Long,
      repoFullName: String,
      number: Int,
      outcome: IssueOutcome,
  ) {
    // Both endings of the run come here, and the failure one after the other may already have got
    // part of the way — so each step is taken at most once and the account already given stands. A
    // merge reported and then followed by trouble is still a merge, and an issue told two things
    // about one run leaves whoever reads it to guess.
    if (!outcomeReported) {
      finishActivities.postIssueComment(
          installationId,
          repoFullName,
          number,
          finishComment(outcome),
      )
      outcomeReported = true
    }
    if (!leftQueue) {
      finishActivities.removeReadyLabel(installationId, repoFullName, number)
      leftQueue = true
    }
  }

  /**
   * Follows the pull request until it stops being open and reports how it ended, saying while it
   * does so that the session is waiting on a person rather than on the agent.
   */
  private fun followPullRequest(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
      pullRequestNumber: Int,
  ): IssueOutcome {
    // Written down here rather than worked out from the session later, because here is where it is
    // known: the agent is done and nothing else happens until somebody reviews. Derived instead,
    // the moment between the attempt ending and the pull request being recorded is neither state.
    activities.awaitReview(sessionId)
    val outcome =
        awaitPullRequestEnd(
            sessionId,
            installationId,
            repoFullName,
            number,
            title,
            pullRequestNumber,
        )
    // Settled: whatever the pull request did, finishing the issue is Farm's own work, and nobody
    // is being waited on for it.
    activities.resumeWork(sessionId)
    return outcome
  }

  /**
   * Polls the pull request, running a fixup for each review that asks for changes and for each way
   * its checks go red, until it stops being open. Gives up after [REVIEW_SILENCE_LIMIT] of nothing
   * happening: the session is over either way, and whether it succeeded is read from the merge, not
   * from the session ending.
   */
  private fun awaitPullRequestEnd(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
      pullRequestNumber: Int,
  ): IssueOutcome {
    var silence = Duration.ZERO
    var lastReviewId = NO_REVIEW_YET
    var reviewFixupsRun = 0
    var checkFixupsRun = 0
    var checksWorked = emptyList<FailedCheck>()

    while (silence < REVIEW_SILENCE_LIMIT) {
      // Just opened or just pushed to, so nothing can have happened yet — sleep, then look.
      Workflow.sleep(REVIEW_POLL_INTERVAL)
      silence = silence.plus(REVIEW_POLL_INTERVAL)

      val report =
          activities.readPullRequest(
              sessionId,
              installationId,
              repoFullName,
              pullRequestNumber,
              lastReviewId,
          )
      when (report.state) {
        GhPullRequestState.MERGED -> return IssueOutcome.MERGED
        GhPullRequestState.CLOSED -> return IssueOutcome.CLOSED_UNMERGED
        GhPullRequestState.OPEN -> Unit
      }

      // A person before a machine: a reviewer who has read the change knows things the build does
      // not, and the fixup they get pushes a commit the checks run against anyway.
      val feedback = report.feedback
      if (feedback != null) {
        // Marked as seen whether or not it is acted on, so a review that cannot be acted on does
        // not come back every time round.
        lastReviewId = feedback.reviewId
        if (reviewFixupsRun >= MAX_FIXUP_RUNS) continue

        reviewFixupsRun++
        // The review is in, so the wait on a person is over until the fixup gives them something to
        // look at again.
        activities.resumeWork(sessionId)
        publishActivities.fixupIssue(
            sessionId,
            installationId,
            repoFullName,
            number,
            title,
            reviewFixupsRun + checkFixupsRun,
            feedback,
        )
        activities.awaitReview(sessionId)

        // Somebody is engaged with this pull request, so the clock that gives up on silence starts
        // again rather than running out mid-conversation.
        silence = Duration.ZERO
        continue
      }

      // A review says something new each time it is left; a check says the same thing on every
      // pass for as long as it is red, so it is worked once and then only when it fails differently
      // — otherwise one test that always falls over the same way would spend the whole budget.
      val failedChecks = report.failedChecks
      if (failedChecks.isEmpty() || failedChecks == checksWorked) continue
      checksWorked = failedChecks
      if (checkFixupsRun >= MAX_CHECK_FIXUP_RUNS) continue

      checkFixupsRun++
      activities.resumeWork(sessionId)
      publishActivities.fixupChecks(
          sessionId,
          installationId,
          repoFullName,
          number,
          title,
          reviewFixupsRun + checkFixupsRun,
          failedChecks,
      )
      activities.awaitReview(sessionId)

      // Silence is not reset: it measures how long nobody has touched the pull request, and Farm
      // repairing its own build is not somebody having touched it. Resetting here would let a
      // pull request keep itself waited on without a person ever looking at it.
    }

    return IssueOutcome.ABANDONED
  }

  companion object {
    // A persistent failure surfaces after a few attempts rather than retrying forever (which would
    // leave the session stuck RUNNING).
    private const val MAX_ATTEMPTS = 5

    private const val ATTEMPT_MAX_ATTEMPTS = 2

    // Retrying without end, so the backoff is capped rather than doubling into hours: whatever is
    // keeping the issue in the queue is worth trying again soon after it clears.
    private val FINISH_RETRY_INTERVAL_CAP: Duration = Duration.ofMinutes(5)

    // Nothing pushes review events to us — there is no webhook receiver — so the gate polls, as
    // the issue sweep does. A minute is how long someone waits between merging and Farm noticing,
    // which is the only thing this ought to be chosen by.
    private val REVIEW_POLL_INTERVAL: Duration = Duration.ofMinutes(1)

    // A pull request nobody touches is not a failure, so waiting stops rather than the run does.
    // Reset by every fixup: the limit is on silence, not on how long a review conversation runs.
    //
    // Half a day, rather than the week it wants to be, because polling this often fills a
    // workflow's history — and every fixup starts the silence over, so the budget is this limit
    // times one more than the fixups allowed, not the limit itself. A day did not fit. The test
    // measures it rather than trusting this paragraph. Webhooks would leave this loop as an
    // occasional backstop and lift the limit on their own; failing that, continuing as new does.
    private val REVIEW_SILENCE_LIMIT: Duration = Duration.ofHours(12)

    // Reviews are numbered from 1, so nothing has been acted on yet.
    private const val NO_REVIEW_YET = 0L

    // A bound on going round in circles, not on how much review a change deserves: past this the
    // pull request is still watched for a merge, but its reviews stop being worked.
    internal const val MAX_FIXUP_RUNS = 3

    // A budget of its own rather than a share of the reviews': a red build must not be able to eat
    // the runs that the next thing a reviewer says is owed.
    internal const val MAX_CHECK_FIXUP_RUNS = 3

    private val LABEL_IT_AGAIN =
        "Taking `${FarmLabels.READY}` off; label it again to have Farm try afresh."

    private fun openedComment(pullRequestUrl: String): String =
        "🌱 Farm opened a pull request: $pullRequestUrl"

    private fun finishComment(outcome: IssueOutcome): String =
        when (outcome) {
          IssueOutcome.MERGED ->
              "🌱 Farm's pull request was merged. Taking `${FarmLabels.READY}` off: this one is " +
                  "done."
          IssueOutcome.CLOSED_UNMERGED ->
              "🌱 Farm's pull request was closed without being merged. $LABEL_IT_AGAIN"
          IssueOutcome.ABANDONED ->
              "🌱 Nobody touched Farm's pull request, so Farm has stopped following it. " +
                  LABEL_IT_AGAIN
          IssueOutcome.NOTHING_TO_CHANGE ->
              "🌱 Farm looked into this but didn't find anything to change. $LABEL_IT_AGAIN"
          IssueOutcome.FAILED -> "🌱 Farm's run on this issue failed. $LABEL_IT_AGAIN"
        }
  }
}
