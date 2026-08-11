package software.medusa.farm.server

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import software.medusa.farm.shared.WorkflowServiceAuthConfig

/**
 * Starts the Fibonacci workflow on Temporal via an **untyped** stub keyed by workflow type name
 * ("FibonacciWorkflow"), so the API never depends on the worker module.
 *
 * Single-flight: every start uses the stable workflow id [WORKFLOW_ID] with a conflict policy of
 * USE_EXISTING, so a click while a run is in flight attaches to that run instead of stacking a
 * concurrent one; ALLOW_DUPLICATE reuse lets a fresh run start once the previous one finishes. The
 * workflow itself resumes from the highest stored index, so overlapping requests never
 * double-write.
 *
 * The [WorkflowClient] is built lazily and connects lazily, so construction never blocks server
 * startup or other RPCs.
 */
class TemporalFibonacciStarter(
    private val address: String,
    private val namespace: String,
    private val authConfig: WorkflowServiceAuthConfig,
) : FibonacciStarter {
  private val client: WorkflowClient by lazy { buildClient() }

  private fun buildClient(): WorkflowClient {
    val builder = WorkflowServiceStubsOptions.newBuilder().setTarget(address)
    authConfig.configureBuilder(builder)
    val service = WorkflowServiceStubs.newServiceStubs(builder.build())
    return WorkflowClient.newInstance(
        service,
        WorkflowClientOptions.newBuilder().setNamespace(namespace).build(),
    )
  }

  override fun start(through: Int): String {
    val stub =
        client.newUntypedWorkflowStub(
            WORKFLOW_TYPE,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(WORKFLOW_ID)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                )
                .setWorkflowIdConflictPolicy(
                    WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING
                )
                .build(),
        )
    return stub.start(through).workflowId
  }

  companion object {
    // The worker registers "FibonacciWorkflow" on this task queue (see the worker module's
    // TemporalWorkerHost); referenced by name so the API stays decoupled from that module.
    private const val WORKFLOW_TYPE = "FibonacciWorkflow"
    private const val TASK_QUEUE = "farm-fibonacci"
    private const val WORKFLOW_ID = "farm-fibonacci"
  }
}
