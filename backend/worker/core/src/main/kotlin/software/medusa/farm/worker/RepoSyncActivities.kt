package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import software.medusa.farm.shared.FetchedRepoWithIssues
import software.medusa.farm.shared.ReadyIssue

/** The GitHub fetch and the store reconcile the [RepoSyncWorkflow] delegates to. */
@ActivityInterface
interface RepoSyncActivities {
  /**
   * Fetches the installation's complete repo list with each repo's open issues, or throws so the
   * fetch is retried.
   */
  @ActivityMethod fun fetchInstallationRepos(installationId: Long): List<FetchedRepoWithIssues>

  /** Reconciles the fetched repos, and then each one's issues, against the stored rows. */
  @ActivityMethod
  fun reconcile(
      installationId: Long,
      repos: List<FetchedRepoWithIssues>,
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
   * Starts (fire-and-forget) processing for each of [issues], and is a no-op for any one already in
   * flight, so the sweep can attempt every ready issue on every run.
   *
   * Only while in flight. What keeps a finished issue from being worked again is that the session
   * took the label off it, which is the sweep's own question rather than a property of the id.
   *
   * All of them in one call, so what a sweep spends on round trips does not grow with how much
   * there is to work.
   */
  @ActivityMethod fun startIssueProcessing(installationId: Long, issues: List<ReadyIssue>)
}
