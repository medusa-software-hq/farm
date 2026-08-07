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
import software.medusa.farm.worker.workflow.BuildWorkflowImpl
import software.medusa.farm.worker.workflow.PostMergeWorkflowImpl
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

    // Two task queues, one process (DESIGN.md §2.4, §6.2). `farm-pipeline` runs all three workflows
    // and the light activities with a generous activity-concurrency budget; `farm-engine` runs ONLY
    // the long `runEngine` with its own, small budget so a saturated engine can't starve
    // orchestration. `runEngine` is routed here by
    // `ActivityOptions.setTaskQueue(TaskQueues.engine)`.
    val pipelineWorker = factory.newWorker(TaskQueues.pipeline)
    pipelineWorker.registerWorkflowImplementationTypes(
        RepoCoordinatorWorkflowImpl::class.java,
        BuildWorkflowImpl::class.java,
        PostMergeWorkflowImpl::class.java,
    )
    pipelineWorker.registerActivitiesImplementations(
        RepoActivitiesImpl(config),
        GitHubActivitiesImpl(config),
        DeployActivitiesImpl(config),
        DomainStoreActivitiesImpl(domainStore),
    )

    val engineWorker = factory.newWorker(TaskQueues.engine)
    engineWorker.registerActivitiesImplementations(EngineActivitiesImpl(config))

    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
              log.info("shutting down Temporal worker")
              factory.shutdown()
              service.shutdown()
            }
        )

    log.info(
        "starting Farm worker: namespace={} taskQueues=[{}, {}] target={}",
        config.temporal.namespace,
        TaskQueues.pipeline,
        TaskQueues.engine,
        config.temporal.address,
    )
    factory.start()
  }
}
