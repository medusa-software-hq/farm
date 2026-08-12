package software.medusa.farm.worker

import java.time.Instant
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.shared.FetchedRepo
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.RepoStore

/** Runs the fetch against GitHub and the reconcile against the store on the activity thread. */
class RepoSyncActivitiesImpl(
    private val clientProvider: GhInstallationApiClientProvider,
    private val repoStore: RepoStore,
    private val linkedOrgStore: LinkedOrgStore,
) : RepoSyncActivities {
  override fun fetchInstallationRepos(installationId: Long): List<FetchedRepo> = runBlocking {
    clientProvider
        .provideForInstallation(GhInstallationId(installationId))
        .listInstallationRepositories()
        .map {
          FetchedRepo(
              githubRepoId = it.id.value,
              fullName = it.fullName.value,
              name = it.name,
              isPrivate = it.isPrivate,
              defaultBranch = it.defaultBranch,
          )
        }
  }

  override fun reconcileRepos(
      installationId: Long,
      repos: List<FetchedRepo>,
      syncStartedAtEpochMillis: Long,
  ) = runBlocking {
    repoStore.reconcile(installationId, repos, Instant.ofEpochMilli(syncStartedAtEpochMillis))
  }

  override fun listLinkedInstallations(): List<Long> = runBlocking {
    linkedOrgStore.list().map { it.installationId }
  }
}
