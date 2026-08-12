package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.shared.RepoSyncWorkflow

/**
 * Captures the sync watermark, fetches the installation's repos in full, then reconciles them. The
 * watermark is taken before the fetch so a repo seen mid-fetch still counts as present. An
 * empty-but-successful fetch is left un-reconciled: never mass-orphan on a fetch that returned
 * nothing (a real removal of the last repo simply waits for the next non-empty sync).
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
  }
}
