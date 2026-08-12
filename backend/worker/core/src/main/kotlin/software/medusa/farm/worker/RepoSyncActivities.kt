package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import software.medusa.farm.shared.FetchedRepo

/** The GitHub fetch and the store reconcile the [RepoSyncWorkflow] delegates to. */
@ActivityInterface
interface RepoSyncActivities {
  /** Fetches the installation's complete repo list, or throws so the fetch is retried. */
  @ActivityMethod fun fetchInstallationRepos(installationId: Long): List<FetchedRepo>

  @ActivityMethod
  fun reconcileRepos(
      installationId: Long,
      repos: List<FetchedRepo>,
      syncStartedAtEpochMillis: Long,
  )
}
