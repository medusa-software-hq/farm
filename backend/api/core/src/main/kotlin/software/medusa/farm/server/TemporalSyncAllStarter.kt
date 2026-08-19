package software.medusa.farm.server

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.shared.SyncAllReposWorkflow
import software.medusa.farm.shared.syncAllReposWorkflowId

/**
 * Starts [SyncAllReposWorkflow] on Temporal via a **typed** stub over the shared interface — the
 * same workflow the periodic schedule runs — so a manual "sync now" and the scheduled sweep are the
 * one code path. The stable workflow id with USE_EXISTING makes a manual trigger that lands while a
 * sweep is running attach to it rather than stack a duplicate; ALLOW_DUPLICATE reuse lets a fresh
 * sweep start once the previous one finishes. The per-installation syncs the sweep fans out to
 * dedupe on their own ids, so overlapping with a scheduled run never double-syncs an org.
 *
 * The [WorkflowClient] is built off the critical path and shared across starters; [start] awaits it
 * (a request that races the build just suspends until it completes). A start that cannot reach
 * Temporal propagates, so the caller surfaces the failure.
 */
class TemporalSyncAllStarter(
    private val clientDeferred: Deferred<WorkflowClient>,
    // The queue the API starts workflows onto; it has to be the one the worker is taking from.
    private val taskQueue: String,
) : SyncAllStarter {
  override suspend fun start() {
    val client = clientDeferred.await()
    withContext(Dispatchers.IO) {
      val stub =
          client.newWorkflowStub(
              SyncAllReposWorkflow::class.java,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(taskQueue)
                  .setWorkflowId(syncAllReposWorkflowId())
                  .setWorkflowIdReusePolicy(
                      WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                  )
                  .setWorkflowIdConflictPolicy(
                      WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING
                  )
                  .build(),
          )
      WorkflowClient.start(stub::syncAll)
    }
  }
}
