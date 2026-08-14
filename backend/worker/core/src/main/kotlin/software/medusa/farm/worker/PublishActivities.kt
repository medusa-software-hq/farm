package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/** The real coding work: attempt an issue with the agent and, if it changed anything, open a PR. */
@ActivityInterface
interface PublishActivities {
  /**
   * Clones the repo, runs the agent against the issue, and — if it produced changes — commits them
   * to a branch, pushes, and opens a pull request. Returns the PR URL, or `null` for no changes.
   * Throws (so the caller can fail the session) if the run does not complete cleanly.
   */
  @ActivityMethod
  fun attemptIssue(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
  ): IssueAttemptOutcome
}
