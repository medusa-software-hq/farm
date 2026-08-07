package software.medusa.farm.worker

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import org.slf4j.LoggerFactory
import software.medusa.farm.worker.activity.impl.DeployActivitiesImpl
import software.medusa.farm.worker.activity.impl.DomainStoreActivitiesImpl
import software.medusa.farm.worker.activity.impl.EngineActivitiesImpl
import software.medusa.farm.worker.activity.impl.GitHubActivitiesImpl
import software.medusa.farm.worker.activity.impl.RepoActivitiesImpl
import software.medusa.farm.worker.activity.impl.WorkerDomainStore
import software.medusa.farm.worker.workflow.PipelineWorkflowImpl
import software.medusa.farm.worker.workflow.RepoCoordinatorWorkflowImpl

/**
 * Bootstraps and runs the Temporal worker: connects to the frontend, registers the workflow and
 * activity implementations on the task queue, and blocks until shutdown. Mirrors the org demo's
 * connection/namespace/task-queue model on the JVM SDK.
 */
class TemporalWorkerHost(private val config: WorkerConfig) {
  private val log = LoggerFactory.getLogger(TemporalWorkerHost::class.java)

  fun run() {
    val service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(config.temporal.address).build()
        )
    val client =
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder().setNamespace(config.temporal.namespace).build(),
        )
    val factory = WorkerFactory.newInstance(client)

    val domainStore = WorkerDomainStore.build(config.database.jdbcUrl)
    val worker = factory.newWorker(config.temporal.taskQueue)

    worker.registerWorkflowImplementationTypes(
        RepoCoordinatorWorkflowImpl::class.java,
        PipelineWorkflowImpl::class.java,
    )
    worker.registerActivitiesImplementations(
        RepoActivitiesImpl(config),
        EngineActivitiesImpl(config),
        GitHubActivitiesImpl(config),
        DeployActivitiesImpl(config),
        DomainStoreActivitiesImpl(domainStore),
    )

    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
              log.info("shutting down Temporal worker")
              factory.shutdown()
              service.shutdown()
            }
        )

    log.info(
        "starting Farm worker: namespace={} taskQueue={} target={}",
        config.temporal.namespace,
        config.temporal.taskQueue,
        config.temporal.address,
    )
    factory.start()
  }
}
