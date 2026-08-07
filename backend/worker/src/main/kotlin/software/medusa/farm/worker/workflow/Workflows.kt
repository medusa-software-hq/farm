package software.medusa.farm.worker.workflow

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import software.medusa.farm.worker.model.CheckUpdate
import software.medusa.farm.worker.model.PipelineInput
import software.medusa.farm.worker.model.PipelineResult
import software.medusa.farm.worker.model.PipelineStatus
import software.medusa.farm.worker.model.RepoCoordinatorStatus
import software.medusa.farm.worker.model.RepoRef

/**
 * The per-repo mutex, expressed natively on Temporal. Exactly one coordinator runs per repo because
 * its workflow id is derived from the repo full name (`repo:<owner>/<name>`) — Temporal guarantees
 * a single running execution per id. It serially picks the next ready issue and starts one child
 * [PipelineWorkflow] at a time, releasing the mutex when that pipeline reaches MERGED (not DONE),
 * so the next issue branches off updated trunk while post-merge checks + self-heal continue in the
 * (abandoned) child.
 */
@WorkflowInterface
interface RepoCoordinatorWorkflow {
  @WorkflowMethod fun coordinate(repo: RepoRef)

  /** Nudge from the api (e.g. a GitHub webhook said a new `flow:ready` issue appeared). */
  @SignalMethod fun onReadyIssuesChanged()

  /** A child pipeline reached MERGED — the repo mutex is now free for the next pick. */
  @SignalMethod fun onPipelineMutexReleased(issueNumber: Int)

  @QueryMethod fun status(): RepoCoordinatorStatus
}

/**
 * One workflow per issue, orchestrating the full lifecycle: prepare -> engine-run -> open-PR ->
 * await-merge-checks -> merge -> post-merge/deploy -> self-heal. Workflow id:
 * `pipeline:<owner>/<name>#<issue>`. Timers/retries replace Flow's reconciler polling; signals
 * carry human-in-the-loop and external check-completion events.
 */
@WorkflowInterface
interface PipelineWorkflow {
  @WorkflowMethod fun run(input: PipelineInput): PipelineResult

  /** Human-in-the-loop: approve a pipeline that is gated pending review. */
  @SignalMethod fun approve()

  /** Human-in-the-loop: abort the pipeline (cancels the in-flight engine activity). */
  @SignalMethod fun abort(reason: String)

  /** External check-completion event (webhook -> api -> signal), avoids busy polling. */
  @SignalMethod fun onCheckCompleted(update: CheckUpdate)

  /** External merge event (webhook -> api -> signal). */
  @SignalMethod fun onPrMerged(mergeCommitSha: String)

  @QueryMethod fun status(): PipelineStatus
}
