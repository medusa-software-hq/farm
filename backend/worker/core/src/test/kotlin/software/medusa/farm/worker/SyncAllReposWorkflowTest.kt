package software.medusa.farm.worker

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.FetchedIssue
import software.medusa.farm.shared.FetchedRepo
import software.medusa.farm.shared.SyncAllReposWorkflow

/** Drives the periodic sweep against Temporal's in-memory test server with scripted activities. */
class SyncAllReposWorkflowTest {
  private val installationIds = listOf(100L, 200L, 300L)
  private val failingId = 200L

  private val started = CopyOnWriteArrayList<Long>()

  // Scripted activities: every org is listed, one org's startRepoSync fails non-retryably (so the
  // activity fails fast instead of retrying forever), the rest start normally.
  private inner class ScriptedActivities : RepoSyncActivities {
    override fun listLinkedInstallations(): List<Long> = installationIds

    override fun startRepoSync(installationId: Long) {
      if (installationId == failingId) {
        throw ApplicationFailure.newNonRetryableFailure("boom for $installationId", "TestFailure")
      }
      started += installationId
    }

    override fun fetchInstallationRepos(installationId: Long): List<FetchedRepo> =
        error("not exercised by the sweep")

    override fun reconcileRepos(
        installationId: Long,
        repos: List<FetchedRepo>,
        syncStartedAtEpochMillis: Long,
    ) = error("not exercised by the sweep")

    override fun fetchRepoIssues(installationId: Long, repoFullName: String): List<FetchedIssue> =
        error("not exercised by the sweep")

    override fun reconcileIssues(
        installationId: Long,
        githubRepoId: Long,
        repoFullName: String,
        issues: List<FetchedIssue>,
        syncStartedAtEpochMillis: Long,
    ) = error("not exercised by the sweep")
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
    worker.registerWorkflowImplementationTypes(SyncAllReposWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(ScriptedActivities())
    env.start()
  }

  @AfterTest fun tearDown() = env.close()

  private fun syncAll() {
    env.workflowClient
        .newWorkflowStub(
            SyncAllReposWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(FarmWorker.TASK_QUEUE).build(),
        )
        .syncAll()
  }

  @Test
  fun `starts a repo sync for every linked installation, isolating one failure`() {
    syncAll()
    // The sweep completed (no exception) and started every non-failing org; only the failing one is
    // missing, proving one org's failure does not abort the others.
    assertEquals(setOf(100L, 300L), started.toSet())
  }
}
