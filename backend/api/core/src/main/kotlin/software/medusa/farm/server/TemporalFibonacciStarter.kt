package software.medusa.farm.server

import io.grpc.Status
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import software.medusa.farm.shared.BakedConfig

/**
 * Starts the Fibonacci workflow on Temporal Cloud via an **untyped** stub keyed by workflow type
 * name ("FibonacciWorkflow"), so the API never depends on the worker module.
 *
 * Single-flight: every start uses the stable workflow id [WORKFLOW_ID] with a conflict policy of
 * USE_EXISTING, so a click while a run is in flight attaches to that run instead of stacking a
 * concurrent one; ALLOW_DUPLICATE reuse lets a fresh run start once the previous one finishes. The
 * workflow itself resumes from the highest stored index, so overlapping requests never
 * double-write.
 *
 * Robustness: the [WorkflowClient] is built lazily and connects lazily, so construction never
 * blocks server startup or other RPCs. A Temporal-unreachable start is mapped to gRPC UNAVAILABLE
 * and fails only that call.
 */
class TemporalFibonacciStarter(private val apiKey: String) : FibonacciStarter {
  private val client: WorkflowClient by lazy { buildClient() }

  private fun buildClient(): WorkflowClient {
    val service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(BakedConfig.TEMPORAL_ADDRESS)
                .setEnableHttps(true)
                .addApiKey { apiKey }
                .build()
        )
    return WorkflowClient.newInstance(
        service,
        WorkflowClientOptions.newBuilder().setNamespace(BakedConfig.TEMPORAL_NAMESPACE).build(),
    )
  }

  override fun start(through: Int): String =
      try {
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
        stub.start(through).workflowId
      } catch (e: Exception) {
        throw Status.UNAVAILABLE.withDescription(
                "Failed to start the Fibonacci workflow on Temporal Cloud: ${e.message}"
            )
            .withCause(e)
            .asException()
      }

  companion object {
    // The worker registers "FibonacciWorkflow" on this task queue (see the worker module's
    // TemporalWorkerHost); referenced by name so the API stays decoupled from that module.
    private const val WORKFLOW_TYPE = "FibonacciWorkflow"
    private const val TASK_QUEUE = "farm-fibonacci"
    private const val WORKFLOW_ID = "farm-fibonacci"
  }
}
