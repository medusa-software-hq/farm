package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.shared.RepoSyncWorkflow

/**
 * Captures the sync watermark, fetches the installation's repos in full, then reconciles them, then
 * syncs each repo's open issues. The watermark is taken before the fetch so a repo seen mid-fetch
 * still counts as present. An empty-but-successful repo fetch is left un-reconciled: never
 * mass-orphan on a fetch that returned nothing (a real removal of the last repo simply waits for
 * the next non-empty sync).
 *
 * Issues are synced per repo off the just-fetched list, so a repo's issues follow its repo row in
 * the same pass. An empty issue fetch *is* reconciled — no open issues is a valid state that should
 * orphan any that were open — since the fetch throws rather than returning empty on failure.
 *
 * After reconciling a repo's issues, processing is kicked for each open issue. The start is
 * once-per-issue (REJECT_DUPLICATE), so re-running the sweep never re-processes an issue already
 * handled — it only picks up ones that are newly open.
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
    activities.reconcileRepos(installationId, repos, syncStartedAtEpochMillis)

    for (repo in repos) {
      val issuesStartedAtEpochMillis = Workflow.currentTimeMillis()
      val issues = activities.fetchRepoIssues(installationId, repo.fullName)
      activities.reconcileIssues(
          installationId,
          repo.githubRepoId,
          repo.fullName,
          issues,
          issuesStartedAtEpochMillis,
      )
      for (issue in issues) {
        activities.startIssueProcessing(
            installationId,
            repo.githubRepoId,
            repo.fullName,
            issue.number,
        )
      }
    }
  }
}
