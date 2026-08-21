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
    // The queue the workflows started here land on: this worker's own, so an ephemeral run does
    // not hand its work to the deployment.
    private val taskQueue: String,
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
                .setTaskQueue(taskQueue)
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
                .setTaskQueue(taskQueue)
                .setWorkflowId(processIssueWorkflowId(githubRepoId, number))
                // A finished run does not stand in the way of a new one. Refusing the id would
                // read as "process each issue once" and mean "once per retention window": the
                // refusal expires with the execution, and the issue would be worked afresh
                // whenever that happened to be. The label is what says an issue wants working,
                // and the session takes it off when it is done with it.
                //
                // The id still refuses a start while a run is in flight, and a run does not
                // finish until the issue is out of the queue — so an issue that cannot be
                // unlabelled holds a run open rather than being offered up on every pass.
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                )
                .build(),
        )
    try {
      WorkflowClient.start(stub::process, installationId, githubRepoId, repoFullName, number, title)
    } catch (ignored: WorkflowExecutionAlreadyStarted) {
      // Being worked already. Nothing to do.
    }
  }
}
