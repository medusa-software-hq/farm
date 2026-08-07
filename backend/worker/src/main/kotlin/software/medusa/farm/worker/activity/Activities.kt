package software.medusa.farm.worker.activity

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import software.medusa.farm.worker.model.CheckSnapshot
import software.medusa.farm.worker.model.EngineOutcome
import software.medusa.farm.worker.model.EngineRunRequest
import software.medusa.farm.worker.model.IssueRef
import software.medusa.farm.worker.model.PipelineStatus
import software.medusa.farm.worker.model.PrStatus
import software.medusa.farm.worker.model.PublishResult
import software.medusa.farm.worker.model.RepoRef
import software.medusa.farm.worker.model.WorkspaceRef

/**
 * Repo / git side effects. All are idempotent on `(repo, issueNumber)` so Temporal retries are
 * safe.
 */
@ActivityInterface
interface RepoActivities {
  /** Clone/prepare an isolated on-disk workspace and cut the pipeline branch. */
  @ActivityMethod
  fun prepareWorkspace(repo: RepoRef, issueNumber: Int, engine: String): WorkspaceRef

  /**
   * Commit, (re-)sign, push the branch and open a PR. Returns [PublishResult.hadChanges]=false when
   * the engine produced no diff.
   */
  @ActivityMethod
  fun publishBranchAndOpenPr(
      repo: RepoRef,
      issueNumber: Int,
      workspace: WorkspaceRef,
  ): PublishResult

  /**
   * Push a pre-merge fix ADDITIVELY (never a blind force-push): re-fetch first so a human's push is
   * folded in, not clobbered (reactor stance, DESIGN.md §2.5). Force-push is reserved for
   * Farm-authored rebases that first reconcile/replay human commits forward.
   */
  @ActivityMethod fun pushFix(repo: RepoRef, issueNumber: Int, workspace: WorkspaceRef)

  /** Rebase the pipeline branch onto the latest trunk; the reconciler for trunk drift (§2.2). */
  @ActivityMethod fun rebaseOntoTrunk(repo: RepoRef, workspace: WorkspaceRef): Boolean

  /** Best-effort teardown of the on-disk workspace; safe to retry / call twice. */
  @ActivityMethod fun cleanupWorkspace(workspace: WorkspaceRef)
}

/**
 * The long agent run. Implemented as a HEARTBEATING activity (not async-completion): the engine is
 * a subprocess owned by this same worker process, so heartbeating gives progress + cancellation
 * (abort) without a second completing system. Configure a long `startToClose` and a short
 * `heartbeatTimeout`. See DESIGN.md, "The engine activity".
 */
@ActivityInterface
interface EngineActivities {
  @ActivityMethod fun runEngine(request: EngineRunRequest): EngineOutcome
}

/**
 * GitHub control-plane reads/writes: PR state, checks, merge arming, labels, issue close,
 * discovery.
 */
@ActivityInterface
interface GitHubActivities {
  /** Find the next unblocked `flow:ready` issue for a repo, or null. Fenced to the org owner. */
  @ActivityMethod fun discoverNextReadyIssue(repo: RepoRef): IssueRef?

  @ActivityMethod fun getPullRequestState(repo: RepoRef, prNumber: Int): PrStatus

  @ActivityMethod fun getMergeCheckStatus(repo: RepoRef, commitSha: String): CheckSnapshot

  /**
   * Ground truth for the trunk-health merge gate (DESIGN.md §2.1–§2.2): the LITERAL default-branch
   * HEAD combined check/deploy status right now, whoever authored the last commit. Read at the
   * merge gate and to re-sync after a missed webhook or a coordinator continue-as-new.
   */
  @ActivityMethod fun getTrunkHealth(repo: RepoRef): Boolean

  /** Arm GitHub-native auto-merge (idempotent). Actual merge is GitHub's or a human's. */
  @ActivityMethod fun armAutoMerge(repo: RepoRef, prNumber: Int)

  /** Post the completion annotation and close the issue (the graph-advancing action). */
  @ActivityMethod fun markIssueDone(repo: RepoRef, issueNumber: Int, prUrl: String?)

  @ActivityMethod fun setPipelineLabel(repo: RepoRef, issueNumber: Int, stage: String)
}

/** Post-merge deploy / rollout, and observation of its result — the self-heal trigger surface. */
@ActivityInterface
interface DeployActivities {
  /** Trigger the trunk apply/deploy for a merged commit. Idempotent per commit sha. */
  @ActivityMethod fun triggerDeploy(repo: RepoRef, mergeCommitSha: String): String

  /** Read the current deploy/apply status for a commit. */
  @ActivityMethod fun getDeployStatus(repo: RepoRef, mergeCommitSha: String): CheckSnapshot
}

/**
 * Domain store writes — the DB is the read model the web console + `ms-farm` query. Every write is
 * a pipeline-step side effect keyed by `(repo, issueNumber, stage)` for idempotency.
 */
@ActivityInterface
interface DomainStoreActivities {
  @ActivityMethod fun upsertPipeline(status: PipelineStatus, repo: RepoRef)

  @ActivityMethod fun recordStageTransition(repo: RepoRef, issueNumber: Int, stage: String)

  /** Append an engine-run/session record (transcript pointer, cost, outcome). */
  @ActivityMethod
  fun recordSession(repo: RepoRef, issueNumber: Int, sessionId: String?, outcomeKind: String)
}
