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
import software.medusa.farm.shared.FetchedRepoWithIssues
import software.medusa.farm.shared.ReadyIssue
import software.medusa.farm.shared.RepoSyncWorkflow

/**
 * Proves the sweep starts processing only for issues carrying the `farm:ready` label, and asks for
 * all of them at once — one round trip whatever the org has waiting, and none when it has nothing.
 */
class RepoSyncProcessingGateTest {
  private val installationId = 100L

  // What one call to startIssueProcessing was asked to start, per call: the stores and the fan-out
  // to a real ProcessIssueWorkflow are irrelevant here — only which issues the workflow *decides*
  // to process, and how many calls it takes to say so.
  private val startCalls = CopyOnWriteArrayList<List<Int>>()

  // The fetch the workflow sees; the tests script it.
  private var fetched = emptyList<FetchedRepoWithIssues>()

  private inner class ScriptedActivities : RepoSyncActivities {
    override fun fetchInstallationRepos(installationId: Long): List<FetchedRepoWithIssues> = fetched

    override fun reconcile(
        installationId: Long,
        repos: List<FetchedRepoWithIssues>,
        syncStartedAtEpochMillis: Long,
    ) = Unit

    override fun startIssueProcessing(installationId: Long, issues: List<ReadyIssue>) {
      startCalls += issues.map { it.number }
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
    val worker = env.newWorker(FarmWorker.DEFAULT_TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(RepoSyncWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(ScriptedActivities())
    env.start()
  }

  @AfterTest fun tearDown() = env.close()

  @Test
  fun `starts processing for every farm-ready issue in the org, in one call`() {
    fetched =
        listOf(
            repo(
                1L,
                "acme/one",
                FetchedIssue(5, "Ready", true),
                FetchedIssue(6, "Not ready", false),
            ),
            repo(
                2L,
                "acme/two",
                FetchedIssue(7, "Nor this", false),
                FetchedIssue(8, "Ready", true),
            ),
        )

    sync()

    assertEquals(listOf(listOf(5, 8)), startCalls.toList())
  }

  @Test
  fun `an org with nothing ready is not asked to start anything`() {
    fetched = listOf(repo(1L, "acme/one", FetchedIssue(6, "Not ready", false)))

    sync()

    assertEquals(emptyList(), startCalls.toList())
  }

  private fun repo(
      githubRepoId: Long,
      fullName: String,
      vararg issues: FetchedIssue,
  ): FetchedRepoWithIssues =
      FetchedRepoWithIssues(
          FetchedRepo(githubRepoId, fullName, fullName.substringAfter('/'), false, "main"),
          issues.toList(),
      )

  private fun sync() =
      env.workflowClient
          .newWorkflowStub(
              RepoSyncWorkflow::class.java,
              WorkflowOptions.newBuilder().setTaskQueue(FarmWorker.DEFAULT_TASK_QUEUE).build(),
          )
          .sync(installationId)
}
