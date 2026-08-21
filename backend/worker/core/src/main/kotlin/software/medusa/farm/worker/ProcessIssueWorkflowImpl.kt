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
    activities.postIssueComment(installationId, repoFullName, number, finishComment(outcome))
    activities.removeReadyLabel(installationId, repoFullName, number)
  }

  /**
   * Follows the pull request until it stops being open, running a fixup for each review that asks
   * for changes, and reports how it ended. Gives up after [REVIEW_SILENCE_LIMIT] of nothing
   * happening: the session is over either way, and whether it succeeded is read from the merge, not
   * from the session ending.
   */
  private fun followPullRequest(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
      pullRequestNumber: Int,
  ): IssueOutcome {
    var silence = Duration.ZERO
    var lastReviewId = NO_REVIEW_YET
    var fixupsRun = 0

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

      val feedback = report.feedback ?: continue
      // Marked as seen whether or not it is acted on, so a review that cannot be acted on does
      // not come back every time round.
      lastReviewId = feedback.reviewId
      if (fixupsRun >= MAX_FIXUP_RUNS) continue

      fixupsRun++
      publishActivities.fixupIssue(
          sessionId,
          installationId,
          repoFullName,
          number,
          title,
          fixupsRun,
          feedback,
      )

      // Somebody is engaged with this pull request, so the clock that gives up on silence starts
      // again rather than running out mid-conversation.
      silence = Duration.ZERO
    }

    return IssueOutcome.ABANDONED
  }

  companion object {
    // A persistent failure surfaces after a few attempts rather than retrying forever (which would
    // leave the session stuck RUNNING).
    private const val MAX_ATTEMPTS = 5

    private const val ATTEMPT_MAX_ATTEMPTS = 2

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
