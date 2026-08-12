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
import software.medusa.farm.shared.FetchedRepo
import software.medusa.farm.shared.SyncAllReposWorkflow

/** Drives the periodic sweep against Temporal's in-memory test server with scripted activities. */
class SyncAllReposWorkflowTest {
  private val installationIds = listOf(100L, 200L, 300L)
  private val failingId = 200L

  private val fetched = CopyOnWriteArrayList<Long>()
  private val reconciled = CopyOnWriteArrayList<Long>()

  // Scripted activities: every org is listed, one org's fetch fails non-retryably (so its child
  // workflow fails fast instead of retrying forever), the rest reconcile normally.
  private inner class ScriptedActivities : RepoSyncActivities {
    override fun listLinkedInstallations(): List<Long> = installationIds

    override fun fetchInstallationRepos(installationId: Long): List<FetchedRepo> {
      fetched += installationId
      if (installationId == failingId) {
        throw ApplicationFailure.newNonRetryableFailure("boom for $installationId", "TestFailure")
      }
      return listOf(
          FetchedRepo(installationId, "acme/$installationId", "$installationId", false, "main")
      )
    }

    override fun reconcileRepos(
        installationId: Long,
        repos: List<FetchedRepo>,
        syncStartedAtEpochMillis: Long,
    ) {
      reconciled += installationId
    }
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
    worker.registerWorkflowImplementationTypes(
        SyncAllReposWorkflowImpl::class.java,
        RepoSyncWorkflowImpl::class.java,
    )
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
  fun `fans out a child sync per linked installation`() {
    syncAll()
    assertEquals(installationIds.toSet(), fetched.toSet())
  }

  @Test
  fun `one failing org does not abort the sweep`() {
    syncAll()
    // The sweep completed (no exception) and every non-failing org was reconciled; only the failing
    // one is missing.
    assertEquals(setOf(100L, 300L), reconciled.toSet())
  }
}
