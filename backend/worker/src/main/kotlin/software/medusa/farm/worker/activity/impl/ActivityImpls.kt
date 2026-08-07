package software.medusa.farm.worker.activity.impl

import io.temporal.activity.Activity
import software.medusa.farm.worker.WorkerConfig
import software.medusa.farm.worker.activity.DeployActivities
import software.medusa.farm.worker.activity.DomainStoreActivities
import software.medusa.farm.worker.activity.EngineActivities
import software.medusa.farm.worker.activity.GitHubActivities
import software.medusa.farm.worker.activity.RepoActivities
import software.medusa.farm.worker.model.CheckSnapshot
import software.medusa.farm.worker.model.CheckStatus
import software.medusa.farm.worker.model.EngineOutcome
import software.medusa.farm.worker.model.EngineOutcomeKind
import software.medusa.farm.worker.model.EngineRunRequest
import software.medusa.farm.worker.model.IssueRef
import software.medusa.farm.worker.model.PipelineStatus
import software.medusa.farm.worker.model.PrState
import software.medusa.farm.worker.model.PrStatus
import software.medusa.farm.worker.model.PublishResult
import software.medusa.farm.worker.model.RepoRef
import software.medusa.farm.worker.model.WorkspaceRef

/**
 * Stub activity implementations. Bodies are TODO — they capture the intended contract, idempotency
 * keys, and (for the engine) the heartbeat loop, so the design is executable-shaped. Wire real git
 * / GitHub App / engine-subprocess / SQLDelight logic here (see DESIGN.md and Flow's `worker/`).
 */
class RepoActivitiesImpl(private val config: WorkerConfig) : RepoActivities {
  // TODO (DESIGN.md §6.4): port Flow's `github-app-auth` and hold a single worker-singleton
  // `RefreshingGitHubAppToken` here (minted once from config.github, auto-refreshed across activity
  // calls) rather than re-minting per invocation. Shared by RepoActivitiesImpl +
  // GitHubActivitiesImpl.

  override fun prepareWorkspace(repo: RepoRef, issueNumber: Int, engine: String): WorkspaceRef {
    // TODO: mint per-repo installation token (worker-singleton token), clone, cut branch
    // `farm/issue-<n>`.
    return WorkspaceRef(
        workspaceId = "ws-${repo.name}-$issueNumber",
        branch = "farm/issue-$issueNumber",
    )
  }

  override fun publishBranchAndOpenPr(
      repo: RepoRef,
      issueNumber: Int,
      workspace: WorkspaceRef,
  ): PublishResult {
    // TODO: add -A, detect diff, commit + GPG-sign, push, POST /repos/{repo}/pulls.
    return PublishResult(prNumber = null, prUrl = null, hadChanges = false)
  }

  override fun pushFix(repo: RepoRef, issueNumber: Int, workspace: WorkspaceRef) {
    // TODO: re-fetch first (fold in any human commit), commit + sign, push ADDITIVELY — never a
    // blind force-push (reactor stance, DESIGN.md §2.5).
  }

  override fun rebaseOntoTrunk(repo: RepoRef, workspace: WorkspaceRef): Boolean {
    // TODO: fetch trunk, rebase the branch, push; return whether there was drift. A Farm-authored
    // rebase may force-push, but only after replaying human commits forward.
    return false
  }

  override fun cleanupWorkspace(workspace: WorkspaceRef) {
    // TODO: remove the on-disk workspace directory (idempotent).
  }
}

class EngineActivitiesImpl(private val config: WorkerConfig) : EngineActivities {
  override fun runEngine(request: EngineRunRequest): EngineOutcome {
    val ctx = Activity.getExecutionContext()
    // TODO: spawn the pinned engine CLI subprocess (stream-json), parse transcript, drive the
    // health-gate bounce loop. `claude` ONLY to start (no builtin/leader — DESIGN.md §6.3); the CLI
    // is baked into the worker image and selected by config.engine.defaultEngine. Heartbeat
    // progress
    // so Temporal can cancel on abort and detect a dead worker. Recover on retry via
    // ctx.getHeartbeatDetails(...).
    ctx.heartbeat("engine-started")
    return EngineOutcome(
        kind = EngineOutcomeKind.NO_CHANGES,
        workspace = request.workspace,
        sessionId = null,
    )
  }
}

class GitHubActivitiesImpl(private val config: WorkerConfig) : GitHubActivities {
  override fun discoverNextReadyIssue(repo: RepoRef): IssueRef? = null // TODO: search flow:ready

  override fun getPullRequestState(repo: RepoRef, prNumber: Int): PrStatus =
      PrStatus(PrState.OPEN) // TODO

  override fun getMergeCheckStatus(repo: RepoRef, commitSha: String): CheckSnapshot =
      CheckSnapshot(CheckStatus.PENDING) // TODO

  override fun getTrunkHealth(repo: RepoRef): Boolean =
      true // TODO: combined check/deploy status of the LITERAL default-branch HEAD (any author).

  override fun armAutoMerge(repo: RepoRef, prNumber: Int) {
    // TODO: PUT auto-merge if repo settings allow.
  }

  override fun markIssueDone(repo: RepoRef, issueNumber: Int, prUrl: String?) {
    // TODO: comment annotation + close issue.
  }

  override fun setPipelineLabel(repo: RepoRef, issueNumber: Int, stage: String) {
    // TODO: project stage -> flow:* label.
  }
}

class DeployActivitiesImpl(private val config: WorkerConfig) : DeployActivities {
  override fun triggerDeploy(repo: RepoRef, mergeCommitSha: String): String =
      "deploy-$mergeCommitSha" // TODO: kick the trunk apply/deploy.

  override fun getDeployStatus(repo: RepoRef, mergeCommitSha: String): CheckSnapshot =
      CheckSnapshot(CheckStatus.GREEN) // TODO
}

class DomainStoreActivitiesImpl(private val store: WorkerDomainStore) : DomainStoreActivities {
  override fun upsertPipeline(status: PipelineStatus, repo: RepoRef) {
    // TODO: store.upsertPipeline(...)
  }

  override fun recordStageTransition(repo: RepoRef, issueNumber: Int, stage: String) {
    // TODO: store.recordStageTransition(...)
  }

  override fun recordSession(
      repo: RepoRef,
      issueNumber: Int,
      sessionId: String?,
      outcomeKind: String,
  ) {
    // TODO: store.recordSession(...)
  }
}
