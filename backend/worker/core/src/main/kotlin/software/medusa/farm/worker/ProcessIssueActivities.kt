package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * The store writes and GitHub calls the [software.medusa.farm.shared.ProcessIssueWorkflow] drives.
 */
@ActivityInterface
interface ProcessIssueActivities {
  /** Opens a running session with the workflow-supplied id (idempotent, so retries are safe). */
  @ActivityMethod
  fun createSession(
      sessionId: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
      repoFullName: String,
      title: String,
  )

  /** Posts a comment on the issue, or throws so the post is retried. */
  @ActivityMethod
  fun postIssueComment(installationId: Long, repoFullName: String, number: Int, body: String)

  /**
   * Takes the label that offers an issue up for work off it, so the sweep stops seeing it.
   * Idempotent.
   */
  @ActivityMethod fun removeReadyLabel(installationId: Long, repoFullName: String, number: Int)

  /** Records the pull request the session opened. */
  @ActivityMethod
  fun recordPullRequest(sessionId: String, number: Int, url: String, headSha: String)

  /**
   * Looks at the session's pull request: where it stands, and whether a review newer than
   * [afterReviewId] has asked for changes. Records the merge when it finds one. Idempotent.
   */
  @ActivityMethod
  fun readPullRequest(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      afterReviewId: Long,
  ): PullRequestReport

  @ActivityMethod fun completeSession(sessionId: String)

  @ActivityMethod fun failSession(sessionId: String)
}
