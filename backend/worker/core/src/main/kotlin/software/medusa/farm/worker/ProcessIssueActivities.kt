package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import software.medusa.farm.github.GhPullRequestState

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

  /** Records the pull request the session opened. */
  @ActivityMethod
  fun recordPullRequest(sessionId: String, number: Int, url: String, headSha: String)

  /**
   * Fetches the session's pull request and, if it has been merged, records that on the session.
   * Idempotent: seeing the same merge twice records the same time.
   *
   * @return the pull request's state as GitHub reports it now.
   */
  @ActivityMethod
  fun syncPullRequest(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
  ): GhPullRequestState

  @ActivityMethod fun completeSession(sessionId: String)

  @ActivityMethod fun failSession(sessionId: String)
}
