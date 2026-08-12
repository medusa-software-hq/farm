package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.shared.ProcessIssueWorkflow

/**
 * The fake processing run: open a session, post a "starting" comment, wait, post a "finished"
 * comment, close the session. A stand-in for the real AI work — the shape (session + issue
 * side-effects) is what later steps slot into. The session id is drawn from the workflow's
 * deterministic RNG so an activity retry reuses it rather than opening a second session.
 */
class ProcessIssueWorkflowImpl : ProcessIssueWorkflow {
  private val activities =
      Workflow.newActivityStub(
          ProcessIssueActivities::class.java,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build(),
      )

  override fun process(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      number: Int,
  ) {
    val sessionId = Workflow.randomUUID().toString()
    activities.createSession(sessionId, installationId, githubRepoId, number)
    activities.postIssueComment(installationId, repoFullName, number, STARTING_COMMENT)
    Workflow.sleep(PROCESS_DELAY)
    activities.postIssueComment(installationId, repoFullName, number, FINISHED_COMMENT)
    activities.completeSession(sessionId)
  }

  companion object {
    // Stand-in for real work; long enough that a session is observably "running" before it
    // finishes.
    private val PROCESS_DELAY: Duration = Duration.ofSeconds(5)

    private const val STARTING_COMMENT = "🌱 Farm is starting to process this issue…"
    private const val FINISHED_COMMENT = "✅ Farm finished processing this issue."
  }
}
