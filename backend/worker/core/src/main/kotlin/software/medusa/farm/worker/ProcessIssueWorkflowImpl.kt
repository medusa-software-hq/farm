package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration
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
      }
      activities.postIssueComment(installationId, repoFullName, number, resultComment(outcome))
      activities.completeSession(sessionId)
    } catch (e: ActivityFailure) {
      activities.failSession(sessionId)
      throw e
    }
  }

  companion object {
    // A persistent failure surfaces after a few attempts rather than retrying forever (which would
    // leave the session stuck RUNNING).
    private const val MAX_ATTEMPTS = 5

    private const val ATTEMPT_MAX_ATTEMPTS = 2

    private fun resultComment(outcome: IssueAttemptOutcome): String =
        if (outcome.pullRequestUrl != null) {
          "🌱 Farm opened a pull request: ${outcome.pullRequestUrl}"
        } else {
          "🌱 Farm looked into this but didn't find anything to change."
        }
  }
}
