package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.shared.SyncAllReposWorkflow

/**
 * Lists the linked installations and starts each one's
 * [software.medusa.farm.shared.RepoSyncWorkflow] through the fire-and-forget
 * [RepoSyncActivities.startRepoSync] activity. Dedup is real, not exception-based: the start uses
 * the shared stable id with USE_EXISTING, so it attaches to any in-flight on-link sync instead of
 * stacking a second run. Per-org isolation is honest too — a start that genuinely fails is logged
 * and the sweep carries on; the next scheduled run retries it.
 */
class SyncAllReposWorkflowImpl : SyncAllReposWorkflow {
  private val logger = Workflow.getLogger(SyncAllReposWorkflowImpl::class.java)

  private val activities =
      Workflow.newActivityStub(
          RepoSyncActivities::class.java,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
      )

  override fun syncAll() {
    for (installationId in activities.listLinkedInstallations()) {
      try {
        activities.startRepoSync(installationId)
      } catch (e: ActivityFailure) {
        logger.warn("Could not start repo sync for installation {}; continuing", installationId, e)
      }
    }
  }
}
