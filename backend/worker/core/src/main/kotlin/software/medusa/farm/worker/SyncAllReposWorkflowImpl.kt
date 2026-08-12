package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.failure.ChildWorkflowFailure
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.shared.RepoSyncWorkflow
import software.medusa.farm.shared.SyncAllReposWorkflow

/**
 * Lists the linked installations, then fans out one child [RepoSyncWorkflow] per installation and
 * awaits them all, so a run represents a complete sweep. Per-org failures are isolated: one org's
 * child failing (or being skipped because its on-link sync is already running) is logged and the
 * sweep carries on — the next scheduled run retries it.
 */
class SyncAllReposWorkflowImpl : SyncAllReposWorkflow {
  private val logger = Workflow.getLogger(SyncAllReposWorkflowImpl::class.java)

  private val activities =
      Workflow.newActivityStub(
          RepoSyncActivities::class.java,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
      )

  override fun syncAll() {
    val installationIds = activities.listLinkedInstallations()
    val running = installationIds.map { it to startChildSync(it) }
    for ((installationId, promise) in running) {
      try {
        promise.get()
      } catch (e: ChildWorkflowFailure) {
        logger.warn("Repo sync failed for installation {}; continuing the sweep", installationId, e)
      }
    }
  }

  private fun startChildSync(installationId: Long): Promise<Void> {
    val child =
        Workflow.newChildWorkflowStub(
            RepoSyncWorkflow::class.java,
            ChildWorkflowOptions.newBuilder()
                // Same stable id as the on-link sync so a sweep dedupes with an in-flight run
                // instead of stacking a second one. ChildWorkflowOptions exposes no conflict policy
                // in this SDK, so a collision with a running same-id sync fails the child start;
                // that failure is isolated above and the in-flight sync already covers the org.
                // ALLOW_DUPLICATE lets a fresh sweep re-sync once the previous run has closed.
                .setWorkflowId("repo-sync:$installationId")
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                )
                .build(),
        )
    return Async.procedure(child::sync, installationId)
  }
}
