package software.medusa.farm.server

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.SyncAllReposWorkflow
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.shared.syncAllReposWorkflowId

/**
 * Starts [SyncAllReposWorkflow] on Temporal via a **typed** stub over the shared interface — the
 * same workflow the periodic schedule runs — so a manual "sync now" and the scheduled sweep are the
 * one code path. The stable workflow id with USE_EXISTING makes a manual trigger that lands while a
 * sweep is running attach to it rather than stack a duplicate; ALLOW_DUPLICATE reuse lets a fresh
 * sweep start once the previous one finishes. The per-installation syncs the sweep fans out to
 * dedupe on their own ids, so overlapping with a scheduled run never double-syncs an org.
 *
 * The [WorkflowClient] is built eagerly with the startup health check disabled, so construction
 * never blocks on Temporal reachability; the gRPC channel connects lazily on the first RPC. A start
 * that cannot reach Temporal propagates, so the caller surfaces the failure.
 */
class TemporalSyncAllStarter(
    address: String,
    private val namespace: String,
    authConfig: WorkflowServiceAuthConfig,
) : SyncAllStarter {
  private val client: WorkflowClient = buildClient(address, authConfig)

  private fun buildClient(
      address: String,
      authConfig: WorkflowServiceAuthConfig,
  ): WorkflowClient {
    val builder =
        WorkflowServiceStubsOptions.newBuilder().setTarget(address).setDisableHealthCheck(true)
    authConfig.configureBuilder(builder)
    val service = WorkflowServiceStubs.newServiceStubs(builder.build())
    return WorkflowClient.newInstance(
        service,
        WorkflowClientOptions.newBuilder().setNamespace(namespace).build(),
    )
  }

  override fun start() {
    val stub =
        client.newWorkflowStub(
            SyncAllReposWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(FarmWorker.TASK_QUEUE)
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
