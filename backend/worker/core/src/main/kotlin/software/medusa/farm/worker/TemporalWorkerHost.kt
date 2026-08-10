package software.medusa.farm.worker

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import software.medusa.farm.shared.FibonacciStore

/**
 * Registers the Fibonacci workflow + activities and runs the worker.
 *
 * The connection mode follows [apiKey]: a non-blank key is the Temporal Cloud shape (API key over
 * TLS); a blank or null key is the local dev-server shape — plaintext, no TLS, no key
 * (`localhost:7233`, namespace `default`).
 */
class TemporalWorkerHost(
    address: String,
    namespace: String,
    apiKey: String?,
    store: FibonacciStore,
) {
  private val client: WorkflowClient
  private val factory: WorkerFactory

  init {
    val service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(address)
                .apply {
                  if (!apiKey.isNullOrBlank()) {
                    setEnableHttps(true)
                    addApiKey { apiKey }
                  }
                }
                .build()
        )
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
