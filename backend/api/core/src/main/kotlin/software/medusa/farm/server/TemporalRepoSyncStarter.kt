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
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.RepoSyncWorkflow
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.shared.repoSyncWorkflowId

/**
 * Starts [RepoSyncWorkflow] on Temporal via a **typed** stub over the shared interface, so the
 * workflow type the API starts and the type the worker registers derive from the same contract and
 * cannot drift.
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
          client.newWorkflowStub(
              RepoSyncWorkflow::class.java,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(FarmWorker.TASK_QUEUE)
                  .setWorkflowId(repoSyncWorkflowId(installationId))
                  .setWorkflowIdReusePolicy(
                      WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                  )
                  .setWorkflowIdConflictPolicy(
                      WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING
                  )
                  .build(),
          )
      WorkflowClient.start(stub::sync, installationId)
    } catch (e: WorkflowServiceException) {
      logger.warn("Could not start repo sync for installation {}", installationId, e)
    } catch (e: StatusRuntimeException) {
      logger.warn("Could not start repo sync for installation {}", installationId, e)
    }
  }
}
