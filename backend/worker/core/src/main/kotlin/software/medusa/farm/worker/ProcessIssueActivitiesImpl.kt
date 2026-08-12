package software.medusa.farm.worker

import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
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
  ) = runBlocking { sessionStore.create(sessionId, installationId, githubRepoId, number) }

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

  override fun completeSession(sessionId: String) = runBlocking { sessionStore.complete(sessionId) }
}
