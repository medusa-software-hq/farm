package software.medusa.farm.server

import io.grpc.StatusRuntimeException
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowServiceException
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import org.slf4j.LoggerFactory
import software.medusa.farm.shared.WorkflowServiceAuthConfig

/**
 * Starts [software.medusa.farm.worker.RepoSyncWorkflow] on Temporal via an **untyped** stub keyed
 * by workflow type name, so the API never depends on the worker module.
 *
 * One in-flight sync per installation: the stable workflow id [workflowId] with USE_EXISTING makes
 * a link that arrives while a sync is running attach to it rather than stack a duplicate;
 * ALLOW_DUPLICATE reuse lets a fresh sync start once the previous one finishes.
 *
 * Best-effort per the [RepoSyncStarter] contract: a start that cannot reach Temporal is logged and
 * swallowed so it never fails the link that triggered it. The [WorkflowClient] is built eagerly but
 * with the startup health check disabled, so construction never blocks on Temporal reachability;
 * the gRPC channel connects lazily on the first RPC.
 */
class TemporalRepoSyncStarter(
    address: String,
    private val namespace: String,
    authConfig: WorkflowServiceAuthConfig,
) : RepoSyncStarter {
  private val logger = LoggerFactory.getLogger(TemporalRepoSyncStarter::class.java)

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

  override fun start(installationId: Long) {
    try {
      val stub =
          client.newUntypedWorkflowStub(
              WORKFLOW_TYPE,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(TASK_QUEUE)
                  .setWorkflowId(workflowId(installationId))
                  .setWorkflowIdReusePolicy(
                      WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                  )
                  .setWorkflowIdConflictPolicy(
                      WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING
                  )
                  .build(),
          )
      stub.start(installationId)
    } catch (e: WorkflowServiceException) {
      logger.warn("Could not start repo sync for installation {}", installationId, e)
    } catch (e: StatusRuntimeException) {
      logger.warn("Could not start repo sync for installation {}", installationId, e)
    }
  }

  companion object {
    // The worker registers "RepoSyncWorkflow" on this queue (see the worker module's
    // TemporalWorkerHost); referenced by name so the API stays decoupled from that module.
    private const val WORKFLOW_TYPE = "RepoSyncWorkflow"
    private const val TASK_QUEUE = "farm-tasks"

    // Stable per installation so overlapping links collapse onto one run.
    private fun workflowId(installationId: Long): String = "repo-sync:$installationId"
  }
}
