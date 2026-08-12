package software.medusa.farm.server

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.FibonacciWorkflow
import software.medusa.farm.shared.WorkflowServiceAuthConfig

/**
 * Starts the Fibonacci workflow on Temporal via a **typed** stub over the shared
 * [FibonacciWorkflow] interface, so the workflow type the API starts and the type the worker
 * registers derive from the same contract and cannot drift.
 *
 * Single-flight: every start uses the stable workflow id [WORKFLOW_ID] with a conflict policy of
 * USE_EXISTING, so a click while a run is in flight attaches to that run instead of stacking a
 * concurrent one; ALLOW_DUPLICATE reuse lets a fresh run start once the previous one finishes. The
 * workflow itself resumes from the highest stored index, so overlapping requests never
 * double-write.
 *
 * The [WorkflowClient] is built eagerly but the startup health check is disabled, so construction
 * never blocks on Temporal reachability; the gRPC channel connects lazily on the first RPC.
 */
class TemporalFibonacciStarter(
    address: String,
    private val namespace: String,
    authConfig: WorkflowServiceAuthConfig,
) : FibonacciStarter {
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

  override fun start(through: Int): String {
    val stub =
        client.newWorkflowStub(
            FibonacciWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(FarmWorker.TASK_QUEUE)
                .setWorkflowId(WORKFLOW_ID)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                )
                .setWorkflowIdConflictPolicy(
                    WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING
                )
                .build(),
        )
    return WorkflowClient.start(stub::computeThrough, through).workflowId
  }

  companion object {
    private const val WORKFLOW_ID = "farm-fibonacci"
  }
}
