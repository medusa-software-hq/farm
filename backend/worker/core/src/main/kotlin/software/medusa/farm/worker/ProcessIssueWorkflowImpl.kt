package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.shared.ProcessIssueWorkflow

/**
 * The fake processing run: open a session, post a "starting" comment, wait, post a "finished"
 * comment, close the session. A stand-in for the real AI work — the shape (session + issue
 * side-effects) is what later steps slot into. The session id is drawn from the workflow's
 * deterministic RNG so an activity retry reuses it rather than opening a second session.
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
      activities.postIssueComment(installationId, repoFullName, number, STARTING_COMMENT)
      Workflow.sleep(PROCESS_DELAY)
      activities.postIssueComment(installationId, repoFullName, number, FINISHED_COMMENT)
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

    // Stand-in for real work; long enough that a session is observably "running" before it
    // finishes.
    private val PROCESS_DELAY: Duration = Duration.ofSeconds(5)

    private const val STARTING_COMMENT = "🌱 Farm is starting to process this issue…"
    private const val FINISHED_COMMENT = "✅ Farm finished processing this issue."
  }
}
