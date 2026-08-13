package software.medusa.farm.worker

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.FetchedIssue
import software.medusa.farm.shared.FetchedRepo
import software.medusa.farm.shared.RepoSyncWorkflow

/** Proves the sweep starts processing only for issues carrying the `farm:ready` label. */
class RepoSyncProcessingGateTest {
  private val installationId = 100L
  private val processed = CopyOnWriteArrayList<Int>()

  // Scripted activities: one repo with a ready issue (#5) and a non-ready one (#6). The stores and
  // the fan-out to a real ProcessIssueWorkflow are irrelevant here — only which issues the workflow
  // *decides* to process, recorded by startIssueProcessing.
  private inner class ScriptedActivities : RepoSyncActivities {
    override fun fetchInstallationRepos(installationId: Long): List<FetchedRepo> =
        listOf(FetchedRepo(1L, "acme/one", "one", false, "main"))

    override fun reconcileRepos(
        installationId: Long,
        repos: List<FetchedRepo>,
        syncStartedAtEpochMillis: Long,
    ) = Unit

    override fun fetchRepoIssues(installationId: Long, repoFullName: String): List<FetchedIssue> =
        listOf(
            FetchedIssue(5, "Ready", isReady = true),
            FetchedIssue(6, "Not ready", isReady = false),
        )

    override fun reconcileIssues(
        installationId: Long,
        githubRepoId: Long,
        repoFullName: String,
        issues: List<FetchedIssue>,
        syncStartedAtEpochMillis: Long,
    ) = Unit

    override fun startIssueProcessing(
        installationId: Long,
        githubRepoId: Long,
        repoFullName: String,
        number: Int,
        title: String,
    ) {
      processed += number
    }

    override fun listLinkedInstallations(): List<Long> = error("not exercised")

    override fun startRepoSync(installationId: Long) = error("not exercised")
  }

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

  init {
    val worker = env.newWorker(FarmWorker.TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(RepoSyncWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(ScriptedActivities())
    env.start()
  }

  @AfterTest fun tearDown() = env.close()

  @Test
  fun `starts processing only for farm-ready issues`() {
    env.workflowClient
        .newWorkflowStub(
            RepoSyncWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(FarmWorker.TASK_QUEUE).build(),
        )
        .sync(installationId)

    assertEquals(listOf(5), processed.toList())
  }
}
