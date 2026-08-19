package software.medusa.farm.worker

import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
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

  override fun syncPullRequest(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
  ): GhPullRequestState = runBlocking {
    val pullRequest =
        clientProvider
            .provideForInstallation(GhInstallationId(installationId))
            .getPullRequest(GhRepoFullName(repoFullName), number)

    // GitHub's own merge time, not the moment this happened to notice — they differ by however
    // long the gate slept.
    pullRequest.mergedAt?.let { sessionStore.recordPullRequestMerged(sessionId, it) }

    pullRequest.state
  }

  override fun completeSession(sessionId: String) = runBlocking { sessionStore.complete(sessionId) }

  override fun failSession(sessionId: String) = runBlocking { sessionStore.fail(sessionId) }
}
