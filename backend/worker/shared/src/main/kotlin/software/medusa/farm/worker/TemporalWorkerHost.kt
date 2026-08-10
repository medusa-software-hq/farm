package software.medusa.farm.worker

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import software.medusa.farm.shared.FibonacciStore

/**
 * Connects to Temporal Cloud (API key over TLS), registers the Fibonacci workflow + activities, and
 * runs the worker.
 */
class TemporalWorkerHost(config: WorkerConfig, store: FibonacciStore) {
  private val client: WorkflowClient
  private val factory: WorkerFactory

  init {
    val service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(config.temporalAddress)
                .setEnableHttps(true)
                .addApiKey { config.temporalApiKey }
                .build()
        )
    client =
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder().setNamespace(config.temporalNamespace).build(),
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
