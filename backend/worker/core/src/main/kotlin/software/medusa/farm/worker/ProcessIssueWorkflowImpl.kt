package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.github.GhPullRequestState
import software.medusa.farm.shared.ProcessIssueWorkflow

/**
 * The processing run: open a session, attempt the issue with the agent (clone → code → open a PR if
 * anything changed), post the result as a comment, close the session. The session id is drawn from
 * the workflow's deterministic RNG so an activity retry reuses it rather than opening a second
 * session.
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
      val outcome =
          publishActivities.attemptIssue(sessionId, installationId, repoFullName, number, title)
      if (
          outcome.pullRequestUrl != null &&
              outcome.pullRequestNumber != null &&
              outcome.pullRequestHeadSha != null
      ) {
        activities.recordPullRequest(
            sessionId,
            outcome.pullRequestNumber,
            outcome.pullRequestUrl,
            outcome.pullRequestHeadSha,
        )
        activities.postIssueComment(installationId, repoFullName, number, resultComment(outcome))
        followPullRequest(
            sessionId,
            installationId,
            repoFullName,
            number,
            title,
            outcome.pullRequestNumber,
        )
      } else {
        activities.postIssueComment(installationId, repoFullName, number, resultComment(outcome))
      }
      activities.completeSession(sessionId)
    } catch (e: ActivityFailure) {
      activities.failSession(sessionId)
      throw e
    }
  }

  /**
   * Follows the pull request until it stops being open, running a fixup for each review that asks
   * for changes. Gives up after [REVIEW_SILENCE_LIMIT] of nothing happening: the session is over
   * either way, and whether it succeeded is read from the merge, not from the session ending.
   */
  private fun followPullRequest(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
      pullRequestNumber: Int,
  ) {
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
      if (report.state != GhPullRequestState.OPEN) return

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
  }

  companion object {
    // A persistent failure surfaces after a few attempts rather than retrying forever (which would
    // leave the session stuck RUNNING).
    private const val MAX_ATTEMPTS = 5

    private const val ATTEMPT_MAX_ATTEMPTS = 2

    // Nothing pushes review events to us — there is no webhook receiver — so the gate polls, as
    // the issue sweep does. Every poll costs a timer and an activity in the workflow's history, so
    // the interval is what keeps a week of waiting from growing a history Temporal would complain
    // about, rather than a guess at how fast anyone reviews.
    private val REVIEW_POLL_INTERVAL: Duration = Duration.ofMinutes(10)

    // A pull request nobody touches is not a failure, so waiting stops rather than the run does.
    // Reset by every fixup: the limit is on silence, not on how long a review conversation runs.
    private val REVIEW_SILENCE_LIMIT: Duration = Duration.ofDays(7)

    // Reviews are numbered from 1, so nothing has been acted on yet.
    private const val NO_REVIEW_YET = 0L

    // A bound on going round in circles, not on how much review a change deserves: past this the
    // pull request is still watched for a merge, but its reviews stop being worked.
    private const val MAX_FIXUP_RUNS = 3

    private fun resultComment(outcome: IssueAttemptOutcome): String =
        if (outcome.pullRequestUrl != null) {
          "🌱 Farm opened a pull request: ${outcome.pullRequestUrl}"
        } else {
          "🌱 Farm looked into this but didn't find anything to change."
        }
  }
}
