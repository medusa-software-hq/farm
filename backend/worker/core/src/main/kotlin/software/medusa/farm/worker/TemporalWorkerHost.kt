package software.medusa.farm.worker

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.schedules.Schedule
import io.temporal.client.schedules.ScheduleActionStartWorkflow
import io.temporal.client.schedules.ScheduleAlreadyRunningException
import io.temporal.client.schedules.ScheduleClient
import io.temporal.client.schedules.ScheduleClientOptions
import io.temporal.client.schedules.ScheduleIntervalSpec
import io.temporal.client.schedules.ScheduleOptions
import io.temporal.client.schedules.ScheduleSpec
import io.temporal.client.schedules.ScheduleUpdate
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import java.time.Duration
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.farm.claude.CldAuthToken
import software.medusa.farm.claude.CldProperEngine
import software.medusa.farm.claude.CldSystemEnvMap
import software.medusa.farm.gitcli.GitCliAuthor
import software.medusa.farm.gitcli.GitCliProper
import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.IssueStore
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.RepoStore
import software.medusa.farm.shared.SessionStore
import software.medusa.farm.shared.SyncAllReposWorkflow
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.shared.syncAllReposWorkflowId

/** Registers the farm's workflows and activities on one task queue and runs the worker. */
class TemporalWorkerHost(
    address: String,
    namespace: String,
    authConfig: WorkflowServiceAuthConfig,
    // The queue this worker takes from and starts its own workflows onto.
    taskQueue: String,
    repoStore: RepoStore,
    issueStore: IssueStore,
    sessionStore: SessionStore,
    linkedOrgStore: LinkedOrgStore,
    gitHubClientProvider: GhInstallationApiClientProvider,
    appApiClient: GhAppApiClient,
    claudeOauthToken: String,
    summarizer: RunSummarizer,
    commitAuthor: GitCliAuthor,
    signingKey: String?,
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
    val worker = factory.newWorker(taskQueue)
    worker.registerWorkflowImplementationTypes(
        RepoSyncWorkflowImpl::class.java,
        SyncAllReposWorkflowImpl::class.java,
        ProcessIssueWorkflowImpl::class.java,
    )
    val spawner = SysProcessSpawner()
    worker.registerActivitiesImplementations(
        RepoSyncActivitiesImpl(
            gitHubClientProvider,
            repoStore,
            issueStore,
            linkedOrgStore,
            client,
            taskQueue,
        ),
        ProcessIssueActivitiesImpl(gitHubClientProvider, sessionStore),
        PublishActivitiesImpl(
            gitHubClientProvider,
            appApiClient,
            buildEngine(claudeOauthToken, spawner),
            GitCliProper(spawner, SysExecutableHandle.locate("git")),
            sessionStore,
            summarizer,
            commitAuthor,
            signingKey,
            attemptNumbering = TemporalAttemptNumbering(),
        ),
    )
    // The sweep schedule is one per namespace and names the queue it fires onto, so only a worker
    // on the default queue owns it. A worker with a queue of its own would otherwise create it
    // pointing at itself, and the deployment's sweep would fire into a queue nobody is polling.
    if (taskQueue == FarmWorker.DEFAULT_TASK_QUEUE) {
      ensureRepoSyncSchedule(service, namespace, taskQueue)
    }
  }

  /** Starts polling the task queue. Returns immediately; the factory runs in the background. */
  fun start() = factory.start()

  // Creates the periodic-sweep schedule if it is not already there. The schedule lives in Temporal
  // Cloud, independent of this worker: while the worker is down its runs queue and fire once the
  // worker is back. Idempotent — a restart re-attempts and no-ops when the schedule already exists,
  // which is safe even though the single operator-run worker means no real concurrency.
  private fun ensureRepoSyncSchedule(
      service: WorkflowServiceStubs,
      namespace: String,
      taskQueue: String,
  ) {
    val scheduleClient =
        ScheduleClient.newInstance(
            service,
            ScheduleClientOptions.newBuilder().setNamespace(namespace).build(),
        )
    val schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType(SyncAllReposWorkflow::class.java)
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId(syncAllReposWorkflowId())
                            .setTaskQueue(taskQueue)
                            .setWorkflowExecutionTimeout(SWEEP_EXECUTION_TIMEOUT)
                            .build()
                    )
                    .build()
            )
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setIntervals(listOf(ScheduleIntervalSpec(REPO_SYNC_SWEEP_INTERVAL)))
                    .build()
            )
            .build()
    try {
      scheduleClient.createSchedule(
          REPO_SYNC_ALL_SCHEDULE_ID,
          schedule,
          ScheduleOptions.newBuilder().build(),
      )
    } catch (e: ScheduleAlreadyRunningException) {
      // A prior startup already created it. The schedule outlives the process that created it, so
      // any change to the action or spec has to be pushed here or it never takes effect. Carry the
      // existing state through so an operator-applied pause survives a worker restart.
      scheduleClient.getHandle(REPO_SYNC_ALL_SCHEDULE_ID).update { input ->
        ScheduleUpdate(
            Schedule.newBuilder(input.description.schedule)
                .setAction(schedule.action)
                .setSpec(schedule.spec)
                .build()
        )
      }
    }
  }

  companion object {
    private const val REPO_SYNC_ALL_SCHEDULE_ID = "repo-sync-all"

    // How often the sweep fires. One hour backstops the on-link sync without hammering GitHub; bump
    // it here to change the cadence.
    private val REPO_SYNC_SWEEP_INTERVAL: Duration = Duration.ofHours(1)

    // Hard ceiling on a single sweep run. Production completes in ~51 s; 15 minutes is generous
    // enough to survive transient slowness while still letting a wedged run time out on its own so
    // it cannot hold the Skip overlap policy hostage and silence every subsequent fire.
    private val SWEEP_EXECUTION_TIMEOUT: Duration = Duration.ofMinutes(15)

    // The engine driving the real `claude` binary. Locating claude here means a worker launched
    // without it on PATH fails loudly at startup rather than mid-run.
    private fun buildEngine(claudeOauthToken: String, spawner: SysProcessSpawner): CldProperEngine =
        CldProperEngine(
            processSpawner = spawner,
            claudeExecutableHandle = SysExecutableHandle.locate("claude"),
            // Everything a session is allowed to see of this machine; the rest of the worker's
            // environment — its database URL, its keys — stays out of the assistant's reach.
            systemEnvMap =
                CldSystemEnvMap(
                    path = System.getenv("PATH") ?: error("PATH is required"),
                    home = System.getenv("HOME") ?: error("HOME is required"),
                ),
            authToken = CldAuthToken(claudeOauthToken),
            anomalyReporter = LoggingCldAnomalyReporter(),
        )
  }
}
