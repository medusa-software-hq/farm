package software.medusa.farm.temporaldemo

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory

private const val TASK_QUEUE = "farm-temporal-demo"

/**
 * A minimal end-to-end proof that Farm can reach Temporal Cloud: connect with an API key over TLS,
 * run a worker, execute one workflow, print the result. Arg-less and env-configured (like the real
 * worker); the API key is read from the environment and never logged.
 *
 *   TEMPORAL_ADDRESS    the namespace gRPC endpoint, e.g. farm.kr9zt.tmprl.cloud:7233
 *   TEMPORAL_NAMESPACE  the full namespace id, e.g. farm.kr9zt
 *   TEMPORAL_API_KEY    the farm-worker API key (from `terraform output -raw worker_api_key`)
 */
fun main() {
  val address = System.getenv("TEMPORAL_ADDRESS") ?: error("TEMPORAL_ADDRESS is required")
  val namespace = System.getenv("TEMPORAL_NAMESPACE") ?: error("TEMPORAL_NAMESPACE is required")
  val apiKey = System.getenv("TEMPORAL_API_KEY") ?: error("TEMPORAL_API_KEY is required")

  val service =
      WorkflowServiceStubs.newServiceStubs(
          WorkflowServiceStubsOptions.newBuilder()
              .setTarget(address)
              .setEnableHttps(true)
              .addApiKey { apiKey }
              .build())
  val client =
      WorkflowClient.newInstance(
          service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build())

  val factory = WorkerFactory.newInstance(client)
  factory.newWorker(TASK_QUEUE).registerWorkflowImplementationTypes(GreetingWorkflowImpl::class.java)
  factory.start()

  try {
    val workflow =
        client.newWorkflowStub(
            GreetingWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build())
    println(workflow.greet("Farm"))
    println("OK — reached Temporal Cloud namespace '$namespace' and completed a workflow.")
  } finally {
    factory.shutdown()
  }
}
