package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.shared.ProcessIssueWorkflow

/**
 * The processing run: open a session, run the agent to summarize the issue, post the summary as a
 * comment, close the session. The summary is a deliberately small first real task — no git, no PR —
 * that puts an actual Claude call in the pipeline. The session id is drawn from the workflow's
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

  // The agent run gets a longer timeout (a real Claude call) and fewer retries (each attempt
  // costs).
  private val agentActivities =
      Workflow.newActivityStub(
          AgentActivities::class.java,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setRetryOptions(
                  RetryOptions.newBuilder().setMaximumAttempts(AGENT_MAX_ATTEMPTS).build()
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
      val summary = agentActivities.summarizeIssue(installationId, repoFullName, number, title)
      activities.postIssueComment(installationId, repoFullName, number, summaryComment(summary))
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

    // Fewer attempts for the agent: a run is expensive, and a repeated failure is usually a real
    // problem (bad token, exhausted budget) rather than a transient blip.
    private const val AGENT_MAX_ATTEMPTS = 3

    private fun summaryComment(summary: String): String = "🌱 **Farm summary**\n\n$summary"
  }
}
