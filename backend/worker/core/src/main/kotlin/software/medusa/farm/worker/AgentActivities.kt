package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/** The agent work: driving Claude over an issue. */
@ActivityInterface
interface AgentActivities {
  /**
   * Runs the agent to summarize the issue and returns the summary text. A stand-in for real coding
   * work — the first real Claude call in the pipeline. Throws (so the caller can fail the session)
   * if the run does not complete cleanly.
   */
  @ActivityMethod
  fun summarizeIssue(installationId: Long, repoFullName: String, number: Int, title: String): String
}
