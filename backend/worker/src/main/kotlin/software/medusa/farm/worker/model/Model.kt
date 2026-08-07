package software.medusa.farm.worker.model

/**
 * Plain data carriers exchanged between workflows, activities, signals and queries. These are
 * Temporal payloads: keep them simple, versionable, backward-compatible data classes (enums over
 * sealed hierarchies, nullable fields over required additions) so workflow history stays
 * deserializable across deploys.
 */

/** A GitHub repository, fenced to a single org owner at the edges. */
data class RepoRef(val owner: String, val name: String) {
  val fullName: String
    get() = "$owner/$name"
}

/** A ready issue discovered on a repo. */
data class IssueRef(val repo: RepoRef, val issueNumber: Int, val title: String)

/** Input to a per-issue [software.medusa.farm.worker.workflow.PipelineWorkflow] run. */
data class PipelineInput(
    val repo: RepoRef,
    val issueNumber: Int,
    val engine: String,
    /**
     * Set when this pipeline is a self-heal fix for a broken trunk, not a fresh `flow:ready` issue.
     */
    val healingForMergeSha: String? = null,
)

/**
 * The pipeline lifecycle, re-cast from Flow's two state machines (issue-pipeline + session) onto a
 * single durable workflow. Temporal owns the transitions; these values are what queries/read-model
 * project.
 */
enum class PipelineStage {
  PREPARING,
  ENGINE_RUNNING,
  PR_OPEN,
  AWAITING_MERGE_CHECKS,
  MERGED,
  POST_MERGE_CHECKS,
  SELF_HEALING,
  DONE,
  FAILED,
}

/** Query-able live status of a pipeline workflow. Also projected into the DB read model. */
data class PipelineStatus(
    val stage: PipelineStage,
    val issueNumber: Int,
    val prNumber: Int? = null,
    val prUrl: String? = null,
    val mergeCommitSha: String? = null,
    val failureSummary: String? = null,
)

/** Terminal outcome returned by a pipeline workflow method. */
data class PipelineResult(val stage: PipelineStage, val prUrl: String? = null)

/** Query-able status of a repo coordinator (the per-repo mutex holder). */
data class RepoCoordinatorStatus(
    val repo: RepoRef,
    val activeIssue: Int?,
    val processedCount: Int,
)

/** Handle to an on-disk workspace materialized by [prepareWorkspace]-style activities. */
data class WorkspaceRef(val workspaceId: String, val branch: String)

/** Request to run the long agent engine over a prepared workspace. */
data class EngineRunRequest(
    val repo: RepoRef,
    val issueNumber: Int,
    val engine: String,
    val workspace: WorkspaceRef,
    /** Present when resuming/bouncing a prior session (engine self-correction). */
    val resumeSessionId: String? = null,
)

enum class EngineOutcomeKind {
  SUCCESS,
  NO_CHANGES,
  FAILURE,
}

/**
 * Result of a single engine run. Operational crashes are thrown (so Temporal retries them);
 * deterministic verdicts (success / no-changes / gave-up) are returned here.
 */
data class EngineOutcome(
    val kind: EngineOutcomeKind,
    val workspace: WorkspaceRef,
    val sessionId: String? = null,
    val failureSummary: String? = null,
    val totalCostUsd: Double? = null,
)

/** Result of opening a PR from a published branch. */
data class PublishResult(val prNumber: Int?, val prUrl: String?, val hadChanges: Boolean)

enum class PrState {
  OPEN,
  MERGED,
  CLOSED_UNMERGED,
}

data class PrStatus(val state: PrState, val mergeCommitSha: String? = null)

enum class CheckStatus {
  GREEN,
  RED,
  PENDING,
  NO_RUNS,
}

/** A merge-check / post-merge-check status snapshot for a commit. */
data class CheckSnapshot(val status: CheckStatus, val failingRunNames: List<String> = emptyList())

/** External check-completion event delivered as a signal (from a GitHub webhook via the api). */
data class CheckUpdate(val commitSha: String, val status: CheckStatus)
