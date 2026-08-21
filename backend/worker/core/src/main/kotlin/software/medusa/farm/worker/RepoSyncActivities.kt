package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import software.medusa.farm.shared.FetchedIssue
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

  /** Fetches one repo's open issues, or throws so the fetch is retried. */
  @ActivityMethod
  fun fetchRepoIssues(installationId: Long, repoFullName: String): List<FetchedIssue>

  @ActivityMethod
  fun reconcileIssues(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      issues: List<FetchedIssue>,
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

  /**
   * Starts (fire-and-forget) processing for one issue, and is a no-op while that issue is already
   * in flight, so the sweep can attempt every ready issue on every run.
   *
   * Only while in flight. What keeps a finished issue from being worked again is that the session
   * took the label off it, which is the sweep's own question rather than a property of the id.
   */
  @ActivityMethod
  fun startIssueProcessing(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      number: Int,
      title: String,
  )
}
