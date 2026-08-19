package software.medusa.farm.server

import io.grpc.StatusRuntimeException
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowServiceException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.medusa.farm.shared.RepoSyncWorkflow
import software.medusa.farm.shared.repoSyncWorkflowId

/**
 * Starts [RepoSyncWorkflow] on Temporal via a **typed** stub over the shared interface, so the
 * workflow type the API starts and the type the worker registers derive from the same contract and
 * cannot drift.
 *
 * One in-flight sync per installation: the stable workflow id with USE_EXISTING makes a link that
 * arrives while a sync is running attach to it rather than stack a duplicate; ALLOW_DUPLICATE reuse
 * lets a fresh sync start once the previous one finishes.
 *
 * The [WorkflowClient] is built off the critical path and shared across starters; [start] awaits
 * it. By the time a link arrives it is normally ready; a request that races the (few-second) build
 * just suspends until it completes. A build failure crashes the process (see main), so the await
 * never has to handle a failed client.
 *
 * Best-effort per the [RepoSyncStarter] contract: a start that cannot reach Temporal is logged and
 * swallowed so it never fails the link that triggered it.
 */
class TemporalRepoSyncStarter(
    private val clientDeferred: Deferred<WorkflowClient>,
    // The queue the API starts workflows onto; it has to be the one the worker is taking from.
    private val taskQueue: String,
) : RepoSyncStarter {
  private val logger = LoggerFactory.getLogger(TemporalRepoSyncStarter::class.java)

  override suspend fun start(installationId: Long) {
    val client = clientDeferred.await()
    withContext(Dispatchers.IO) {
      try {
        val stub =
            client.newWorkflowStub(
                RepoSyncWorkflow::class.java,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId(repoSyncWorkflowId(installationId))
                    .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                    )
                    .setWorkflowIdConflictPolicy(
                        WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING
                    )
                    .build(),
            )
        WorkflowClient.start(stub::sync, installationId)
      } catch (e: WorkflowServiceException) {
        logger.warn("Could not start repo sync for installation {}", installationId, e)
      } catch (e: StatusRuntimeException) {
        logger.warn("Could not start repo sync for installation {}", installationId, e)
      }
    }
  }
}
