package software.medusa.farm.worker.workflow

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import software.medusa.farm.worker.model.BuildResult
import software.medusa.farm.worker.model.CheckUpdate
import software.medusa.farm.worker.model.PipelineInput
import software.medusa.farm.worker.model.PipelineStatus
import software.medusa.farm.worker.model.PostMergeInput
import software.medusa.farm.worker.model.PostMergeStatus
import software.medusa.farm.worker.model.RepoCoordinatorStatus
import software.medusa.farm.worker.model.RepoRef

/**
 * The per-repo mutex, expressed natively on Temporal. Exactly one coordinator runs per repo because
 * its workflow id is derived from the repo full name (`repo:<owner>/<name>`) — Temporal guarantees
 * a single running execution per id. It serially picks the next unblocked ready issue and starts
 * one **owned** child [BuildWorkflow] at a time, awaiting its result; that await IS the mutex, and
 * it ends when the build returns at MERGED. It also caches per-repo `trunkHealthy` state (a hint
 * over the literal default-branch status, fed by [onTrunkStatusChanged]) for the merge gate — see
 * DESIGN.md §2.1.
 */
@WorkflowInterface
interface RepoCoordinatorWorkflow {
  @WorkflowMethod fun coordinate(repo: RepoRef)

  /** Nudge from the api (e.g. a GitHub webhook said a new `flow:ready` issue appeared). */
  @SignalMethod fun onReadyIssuesChanged()

  /**
   * Human clears a consecutive-failure circuit-breaker pause so re-pick resumes (DESIGN.md §6.6).
   */
  @SignalMethod fun approve()

  /**
   * Low-latency hint about the repo's *literal* default-branch health — trunk check/deploy webhooks
   * for ANY commit/author, via the api. A cache over ground truth: the merge gate ultimately reads
   * [GitHubActivities.getTrunkHealth]. Not fed authoritatively by [PostMergeWorkflow] (a human's
   * out-of-band merge that breaks trunk has no PostMergeWorkflow but must still gate merges).
   */
  @SignalMethod fun onTrunkStatusChanged(sha: String, healthy: Boolean)

  @QueryMethod fun status(): RepoCoordinatorStatus
}

/**
 * The mutex-held half of a per-issue lifecycle: prepare -> engine -> open PR -> pre-merge fix loop
 * -> rebase onto latest trunk -> await-trunk-healthy -> merge. Workflow id
 * `pipeline:<owner>/<name>#<n>`. It HOLDS the repo mutex for its whole life (the coordinator is
 * blocked awaiting it), so it returns promptly at merge, having handed post-merge work to a
 * detached [PostMergeWorkflow]. Timers/retries replace Flow's reconciler polling; signals carry
 * human-in-the-loop + external events, including the co-author events
 * [onPrClosed]/[onBranchUpdated] (DESIGN.md §2.2, §2.5).
 */
@WorkflowInterface
interface BuildWorkflow {
  @WorkflowMethod fun run(input: PipelineInput): BuildResult

  /** Human-in-the-loop: approve a build gated pending review (risk-gated merge). */
  @SignalMethod fun approve()

  /** Human-in-the-loop: abort the build (cancels the in-flight engine activity). */
  @SignalMethod fun abort(reason: String)

  /** External pre-merge check-completion event (webhook -> api -> signal), avoids busy polling. */
  @SignalMethod fun onCheckCompleted(update: CheckUpdate)

  /** External merge event (webhook -> api -> signal). Fires regardless of *who* merged. */
  @SignalMethod fun onPrMerged(mergeCommitSha: String)

  /** A human abandoned the PR: stop cleanly instead of fighting to re-open (reactor stance). */
  @SignalMethod fun onPrClosed()

  /**
   * A human pushed to the branch: re-fetch and fold their work in, never clobber (reactor stance).
   */
  @SignalMethod fun onBranchUpdated()

  @QueryMethod fun status(): PipelineStatus
}

/**
 * The detached, mutex-free half: observe/await the trunk deploy for a merged commit; green ->
 * report trunk healthy + DONE, red -> report trunk unhealthy + the post-merge heal track (a NEW PR
 * against trunk, bounded). Started with `ParentClosePolicy.ABANDON` by [BuildWorkflow] at merge.
 * Workflow id `postmerge:<owner>/<name>#<n>` (DESIGN.md §2.3).
 */
@WorkflowInterface
interface PostMergeWorkflow {
  @WorkflowMethod fun run(input: PostMergeInput)

  /** Human-in-the-loop: approve a high-risk heal-PR merge. */
  @SignalMethod fun approve()

  /** External post-merge (trunk) check-completion event (webhook -> api -> signal). */
  @SignalMethod fun onCheckCompleted(update: CheckUpdate)

  @QueryMethod fun status(): PostMergeStatus
}
