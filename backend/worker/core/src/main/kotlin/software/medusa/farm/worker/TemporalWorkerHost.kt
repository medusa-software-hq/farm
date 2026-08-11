package software.medusa.farm.worker

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import software.medusa.farm.shared.FibonacciStore
import software.medusa.farm.shared.WorkflowServiceAuthConfig

/**
 * Registers the Fibonacci workflow + activities and runs the worker. [authConfig] decides the
 * connection shape (Temporal Cloud API key over TLS, or plaintext for a local dev server).
 */
class TemporalWorkerHost(
    address: String,
    namespace: String,
    authConfig: WorkflowServiceAuthConfig,
    store: FibonacciStore,
) {
  private val client: WorkflowClient
  private val factory: WorkerFactory

  init {
    val builder = WorkflowServiceStubsOptions.newBuilder().setTarget(address)
    authConfig.configureBuilder(builder)
    val service = WorkflowServiceStubs.newServiceStubs(builder.build())
    client =
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder().setNamespace(namespace).build(),
        )
    factory = WorkerFactory.newInstance(client)
    val worker = factory.newWorker(TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(FibonacciWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(FibonacciActivitiesImpl(store))
  }

  /** Starts polling the task queue. Returns immediately; the factory runs in the background. */
  fun start() = factory.start()

  companion object {
    const val TASK_QUEUE = "farm-fibonacci"
  }
}
