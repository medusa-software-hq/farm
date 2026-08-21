package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.shared.FetchedRepoWithIssues
import software.medusa.farm.shared.ReadyIssue
import software.medusa.farm.shared.RepoSyncWorkflow

/**
 * Captures the sync watermark, fetches the installation's repos and their open issues in full, then
 * reconciles them, then kicks processing for the issues that ask for it. Three activities at most,
 * however many repos the org has, so what a sync costs is not a property of the org's size.
 *
 * The watermark is taken before the fetch so a repo or issue seen mid-fetch still counts as
 * present. An empty-but-successful fetch is left un-reconciled: never mass-orphan on a fetch that
 * returned nothing (a real removal of the last repo simply waits for the next non-empty sync). A
 * fetched repo with no issues *is* reconciled — no open issues is a valid state that should orphan
 * any that were open — since the fetch throws rather than returning empty on failure.
 *
 * Processing is kicked only for issues carrying the `farm:ready` label — the opt-in gate. The start
 * is a no-op for an issue already in flight, so re-running the sweep never doubles up on one being
 * worked; it only picks up ones newly labelled ready. Non-ready issues are still synced and listed.
 */
class RepoSyncWorkflowImpl : RepoSyncWorkflow {
  private val activities =
      Workflow.newActivityStub(
          RepoSyncActivities::class.java,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build(),
      )

  override fun sync(installationId: Long) {
    val syncStartedAtEpochMillis = Workflow.currentTimeMillis()
    val repos = activities.fetchInstallationRepos(installationId)
    if (repos.isEmpty()) return
    activities.reconcile(installationId, repos, syncStartedAtEpochMillis)

    val ready = readyIssues(repos)
    if (ready.isNotEmpty()) activities.startIssueProcessing(installationId, ready)
  }

  private fun readyIssues(repos: List<FetchedRepoWithIssues>): List<ReadyIssue> =
      repos.flatMap { fetched ->
        fetched.issues
            .filter { it.isReady }
            .map {
              ReadyIssue(
                  githubRepoId = fetched.repo.githubRepoId,
                  repoFullName = fetched.repo.fullName,
                  number = it.number,
                  title = it.title,
              )
            }
      }
}
