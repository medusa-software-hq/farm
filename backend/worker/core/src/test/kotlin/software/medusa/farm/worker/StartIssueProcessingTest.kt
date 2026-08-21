package software.medusa.farm.worker

import io.temporal.client.WorkflowClientOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.workflow.Workflow
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.farm.github.GhInstallationApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.InMemoryIssueStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.InMemoryRepoStore
import software.medusa.farm.shared.ProcessIssueWorkflow
import software.medusa.farm.shared.ReadyIssue
import software.medusa.farm.shared.processIssueWorkflowId

/**
 * Proves what the sweep's start gate is really gated on. It has to hold both ways: a run in flight
 * must not be doubled (the sweep asks every pass, and two agents on one issue is two pull
 * requests), and a run that has finished must not stand in the way of the next one — an id refused
 * after the fact would mean "once per retention window", and an issue labelled again would sit
 * unworked until the old execution aged out.
 */
class StartIssueProcessingTest {
  private val installationId = 100L
  private val githubRepoId = 1L
  private val number = 7

  private val env =
      TestWorkflowEnvironment.newInstance(
          TestEnvironmentOptions.newBuilder()
              .setWorkflowClientOptions(
                  WorkflowClientOptions.newBuilder()
                      .setDataConverter(FarmDataConverter.instance)
                      .build()
              )
              .build()
      )

  private val activities =
      RepoSyncActivitiesImpl(
          NoGitHub,
          InMemoryRepoStore(Clock.systemUTC()),
          InMemoryIssueStore(Clock.systemUTC()),
          InMemoryLinkedOrgStore(),
          env.workflowClient,
          FarmWorker.DEFAULT_TASK_QUEUE,
      )

  init {
    runIds.clear()
    val worker = env.newWorker(FarmWorker.DEFAULT_TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(SlowProcessIssueWorkflow::class.java)
    env.start()
  }

  @AfterTest fun tearDown() = env.close()

  @Test
  fun `an issue already being worked is not started a second time`() {
    startProcessing()
    startProcessing()

    awaitRunFinished()
    assertEquals(1, distinctRuns())
  }

  @Test
  fun `an issue whose run has finished can be started again`() {
    startProcessing()
    awaitRunFinished()

    // What labelling an issue again is worth: the run before it is over and forgotten as far as
    // starting goes, so the sweep's next pass works it afresh.
    startProcessing()
    awaitRunFinished()

    assertEquals(2, distinctRuns())
  }

  // Counted by run id rather than by call, because a replayed workflow runs its body again.
  private fun distinctRuns(): Int = runIds.distinct().size

  private fun startProcessing() =
      activities.startIssueProcessing(
          installationId,
          listOf(ReadyIssue(githubRepoId, "acme/one", number, "Do it")),
      )

  private fun awaitRunFinished() {
    env.workflowClient
        .newUntypedWorkflowStub(processIssueWorkflowId(githubRepoId, number))
        .getResult(Void::class.java)
  }

  /** Nothing here reaches GitHub: starting a workflow is a Temporal call and no more. */
  private object NoGitHub : GhInstallationApiClientProvider {
    override fun provideForInstallation(installationId: GhInstallationId): GhInstallationApiClient =
        error("not exercised")
  }

  /**
   * A stand-in run long enough that the second start lands while the first is in flight. Registered
   * by type, so what it did is recorded outside the instance.
   */
  class SlowProcessIssueWorkflow : ProcessIssueWorkflow {
    override fun process(
        installationId: Long,
        githubRepoId: Long,
        repoFullName: String,
        number: Int,
        title: String,
    ) {
      runIds += Workflow.getInfo().runId
      Workflow.sleep(Duration.ofMinutes(5))
    }
  }

  companion object {
    /** The run id of every execution that started, which is what "worked twice" would look like. */
    private val runIds = CopyOnWriteArrayList<String>()
  }
}
