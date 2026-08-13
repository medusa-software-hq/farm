package software.medusa.farm.worker

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import java.time.Instant
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.shared.FarmLabels
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.FetchedIssue
import software.medusa.farm.shared.FetchedRepo
import software.medusa.farm.shared.IssueStore
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.ProcessIssueWorkflow
import software.medusa.farm.shared.RepoStore
import software.medusa.farm.shared.RepoSyncWorkflow
import software.medusa.farm.shared.processIssueWorkflowId
import software.medusa.farm.shared.repoSyncWorkflowId

/** Runs the fetch against GitHub and the reconcile against the store on the activity thread. */
class RepoSyncActivitiesImpl(
    private val clientProvider: GhInstallationApiClientProvider,
    private val repoStore: RepoStore,
    private val issueStore: IssueStore,
    private val linkedOrgStore: LinkedOrgStore,
    private val workflowClient: WorkflowClient,
) : RepoSyncActivities {
  override fun fetchInstallationRepos(installationId: Long): List<FetchedRepo> = runBlocking {
    clientProvider
        .provideForInstallation(GhInstallationId(installationId))
        .listInstallationRepositories()
        .map {
          FetchedRepo(
              githubRepoId = it.id.value,
              fullName = it.fullName.value,
              name = it.name,
              isPrivate = it.isPrivate,
              defaultBranch = it.defaultBranch,
          )
        }
  }

  override fun reconcileRepos(
      installationId: Long,
      repos: List<FetchedRepo>,
      syncStartedAtEpochMillis: Long,
  ) = runBlocking {
    repoStore.reconcile(installationId, repos, Instant.ofEpochMilli(syncStartedAtEpochMillis))
  }

  override fun fetchRepoIssues(installationId: Long, repoFullName: String): List<FetchedIssue> =
      runBlocking {
        clientProvider
            .provideForInstallation(GhInstallationId(installationId))
            .listIssues(GhRepoFullName(repoFullName))
            .map {
              FetchedIssue(
                  number = it.number,
                  title = it.title,
                  isReady = FarmLabels.READY in it.labels,
              )
            }
      }

  override fun reconcileIssues(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      issues: List<FetchedIssue>,
      syncStartedAtEpochMillis: Long,
  ) = runBlocking {
    issueStore.reconcile(
        installationId,
        githubRepoId,
        repoFullName,
        issues,
        Instant.ofEpochMilli(syncStartedAtEpochMillis),
    )
  }

  override fun listLinkedInstallations(): List<Long> = runBlocking {
    linkedOrgStore.list().map { it.installationId }
  }

  override fun startRepoSync(installationId: Long) {
    val stub =
        workflowClient.newWorkflowStub(
            RepoSyncWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(FarmWorker.TASK_QUEUE)
                .setWorkflowId(repoSyncWorkflowId(installationId))
                .setWorkflowIdConflictPolicy(
                    WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING
                )
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                )
                .build(),
        )
    // Fire-and-forget: start (or attach to a running sync via USE_EXISTING) and return; the sweep
    // does not await the sync itself.
    WorkflowClient.start(stub::sync, installationId)
  }

  override fun startIssueProcessing(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      number: Int,
      title: String,
  ) {
    val stub =
        workflowClient.newWorkflowStub(
            ProcessIssueWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(FarmWorker.TASK_QUEUE)
                .setWorkflowId(processIssueWorkflowId(githubRepoId, number))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE
                )
                .build(),
        )
    try {
      WorkflowClient.start(stub::process, installationId, githubRepoId, repoFullName, number, title)
    } catch (ignored: WorkflowExecutionAlreadyStarted) {
      // Already processed (or in flight): process each issue once. Nothing to do.
    }
  }
}
