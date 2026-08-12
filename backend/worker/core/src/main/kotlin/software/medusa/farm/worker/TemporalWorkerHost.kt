package software.medusa.farm.worker

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.shared.FibonacciStore
import software.medusa.farm.shared.RepoStore
import software.medusa.farm.shared.WorkflowServiceAuthConfig

/** Registers the farm's workflows and activities on one task queue and runs the worker. */
class TemporalWorkerHost(
    address: String,
    namespace: String,
    authConfig: WorkflowServiceAuthConfig,
    fibonacciStore: FibonacciStore,
    repoStore: RepoStore,
    gitHubClientProvider: GhInstallationApiClientProvider,
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
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .setDataConverter(FarmDataConverter.instance)
                .build(),
        )
    factory = WorkerFactory.newInstance(client)
    val worker = factory.newWorker(TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(
        FibonacciWorkflowImpl::class.java,
        RepoSyncWorkflowImpl::class.java,
    )
    worker.registerActivitiesImplementations(
        FibonacciActivitiesImpl(fibonacciStore),
        RepoSyncActivitiesImpl(gitHubClientProvider, repoStore),
    )
  }

  /** Starts polling the task queue. Returns immediately; the factory runs in the background. */
  fun start() = factory.start()

  companion object {
    const val TASK_QUEUE = "farm-tasks"
  }
}
