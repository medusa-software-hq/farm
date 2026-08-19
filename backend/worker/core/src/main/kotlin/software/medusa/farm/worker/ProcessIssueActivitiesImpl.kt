package software.medusa.farm.worker

import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhInstallationApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhPullRequestReviewState
import software.medusa.farm.github.GhPullRequestState
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.shared.SessionStore

/** Runs the session writes and the GitHub comment posts on the activity thread. */
class ProcessIssueActivitiesImpl(
    private val clientProvider: GhInstallationApiClientProvider,
    private val sessionStore: SessionStore,
) : ProcessIssueActivities {
  override fun createSession(
      sessionId: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
      repoFullName: String,
      title: String,
  ) = runBlocking {
    sessionStore.create(sessionId, installationId, githubRepoId, number, repoFullName, title)
  }

  override fun postIssueComment(
      installationId: Long,
      repoFullName: String,
      number: Int,
      body: String,
  ) = runBlocking {
    clientProvider
        .provideForInstallation(GhInstallationId(installationId))
        .createIssueComment(GhRepoFullName(repoFullName), number, body)
  }

  override fun recordPullRequest(
      sessionId: String,
      number: Int,
      url: String,
      headSha: String,
  ) = runBlocking { sessionStore.recordPullRequest(sessionId, number, url, headSha) }

  override fun readPullRequest(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      afterReviewId: Long,
  ): PullRequestReport = runBlocking {
    val client = clientProvider.provideForInstallation(GhInstallationId(installationId))
    val repo = GhRepoFullName(repoFullName)
    val pullRequest = client.getPullRequest(repo, number)

    // GitHub's own merge time, not the moment this happened to notice — they differ by however
    // long the gate slept.
    pullRequest.mergedAt?.let { sessionStore.recordPullRequestMerged(sessionId, it) }

    val report =
        if (pullRequest.state != GhPullRequestState.OPEN) {
          PullRequestReport(state = pullRequest.state, feedback = null)
        } else {
          PullRequestReport(
              state = pullRequest.state,
              feedback = findFeedback(client, repo, number, afterReviewId),
          )
        }

    report
  }

  /**
   * The oldest unacted-on review asking for changes, with what it said. Oldest rather than newest
   * so a run of reviews is worked through in the order they were written.
   */
  private suspend fun findFeedback(
      client: GhInstallationApiClient,
      repo: GhRepoFullName,
      number: Int,
      afterReviewId: Long,
  ): ReviewFeedback? {
    val review =
        client
            .listReviews(repo, number)
            .filter { it.id > afterReviewId }
            .sortedBy { it.id }
            .firstOrNull { it.state == GhPullRequestReviewState.CHANGES_REQUESTED } ?: return null

    // Only what this review said. An older review's comments were either already addressed or
    // deliberately not, and re-raising them would undo the reviewer's own judgement.
    val comments =
        client
            .listReviewComments(repo, number)
            .filter { it.reviewId == review.id }
            .map { ReviewFeedbackComment(path = it.path, line = it.line, body = it.body) }

    return ReviewFeedback(reviewId = review.id, body = review.body, comments = comments)
  }

  override fun completeSession(sessionId: String) = runBlocking { sessionStore.complete(sessionId) }

  override fun failSession(sessionId: String) = runBlocking { sessionStore.fail(sessionId) }
}
