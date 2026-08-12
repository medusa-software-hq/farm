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

  /** The installation ids of every linked org — the sweep's fan-out set. */
  @ActivityMethod fun listLinkedInstallations(): List<Long>

  /**
   * Starts (fire-and-forget) the installation's [RepoSyncWorkflow] as a top-level workflow. With
   * the shared stable id and USE_EXISTING, a start that races an in-flight on-link sync attaches to
   * it rather than stacking a second run.
   */
  @ActivityMethod fun startRepoSync(installationId: Long)
}
