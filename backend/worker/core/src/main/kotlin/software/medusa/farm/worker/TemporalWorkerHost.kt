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
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import java.nio.file.Files
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.farm.claude.CldEngineConfig
import software.medusa.farm.claude.CldProperAgent
import software.medusa.farm.claude.CldProperProcess
import software.medusa.farm.claude.CldProperSessionStore
import software.medusa.farm.claude.CldToolPolicy
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
    repoStore: RepoStore,
    issueStore: IssueStore,
    sessionStore: SessionStore,
    linkedOrgStore: LinkedOrgStore,
    gitHubClientProvider: GhInstallationApiClientProvider,
    claudeOauthToken: String,
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
    val worker = factory.newWorker(FarmWorker.TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(
        RepoSyncWorkflowImpl::class.java,
        SyncAllReposWorkflowImpl::class.java,
        ProcessIssueWorkflowImpl::class.java,
    )
    worker.registerActivitiesImplementations(
        RepoSyncActivitiesImpl(gitHubClientProvider, repoStore, issueStore, linkedOrgStore, client),
        ProcessIssueActivitiesImpl(gitHubClientProvider, sessionStore),
        AgentActivitiesImpl(
            gitHubClientProvider,
            buildAgent(claudeOauthToken),
            buildCldSessionStore(),
        ),
    )
    ensureRepoSyncSchedule(service, namespace)
  }

  /** Starts polling the task queue. Returns immediately; the factory runs in the background. */
  fun start() = factory.start()

  // Creates the periodic-sweep schedule if it is not already there. The schedule lives in Temporal
  // Cloud, independent of this worker: while the worker is down its runs queue and fire once the
  // worker is back. Idempotent — a restart re-attempts and no-ops when the schedule already exists,
  // which is safe even though the single operator-run worker means no real concurrency.
  private fun ensureRepoSyncSchedule(service: WorkflowServiceStubs, namespace: String) {
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
                            .setTaskQueue(FarmWorker.TASK_QUEUE)
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
    } catch (ignored: ScheduleAlreadyRunningException) {
      // A prior startup already created it; nothing to do.
    }
  }

  companion object {
    private const val REPO_SYNC_ALL_SCHEDULE_ID = "repo-sync-all"

    // How often the sweep fires. One hour backstops the on-link sync without hammering GitHub; bump
    // it here to change the cadence.
    private val REPO_SYNC_SWEEP_INTERVAL: Duration = Duration.ofHours(1)

    // A summary is a small, tool-free text task; cap it tightly.
    private const val SUMMARY_MAX_BUDGET_USD = 0.50
    private val SUMMARY_TIMEOUT = 2.minutes

    // The connector that drives the real `claude` binary. Locating it here means a worker launched
    // without claude on PATH fails loudly at startup rather than mid-run.
    private fun buildAgent(claudeOauthToken: String): CldProperAgent {
      val config =
          CldEngineConfig.default(
                  environment =
                      mapOf(
                          "PATH" to (System.getenv("PATH") ?: ""),
                          "CLAUDE_CODE_OAUTH_TOKEN" to claudeOauthToken,
                      )
              )
              .copy(
                  // No coding framing (the summary prompt is self-contained) and no tools.
                  appendSystemPrompt = "",
                  maxBudgetUsd = SUMMARY_MAX_BUDGET_USD,
                  wallClockTimeout = SUMMARY_TIMEOUT,
                  toolPolicy = CldToolPolicy.default().copy(allowedTools = emptyList()),
              )
      return CldProperAgent(
          CldProperProcess(SysProcessSpawner(), SysExecutableHandle.locate("claude")),
          config,
      )
    }

    private fun buildCldSessionStore(): CldProperSessionStore =
        CldProperSessionStore(Files.createTempDirectory("farm-cld-sessions"))
  }
}
