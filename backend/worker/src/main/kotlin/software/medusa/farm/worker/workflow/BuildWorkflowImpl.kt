package software.medusa.farm.worker.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.ParentClosePolicy
import io.temporal.common.RetryOptions
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.worker.TaskQueues
import software.medusa.farm.worker.activity.DomainStoreActivities
import software.medusa.farm.worker.activity.EngineActivities
import software.medusa.farm.worker.activity.GitHubActivities
import software.medusa.farm.worker.activity.RepoActivities
import software.medusa.farm.worker.model.BuildResult
import software.medusa.farm.worker.model.CheckStatus
import software.medusa.farm.worker.model.CheckUpdate
import software.medusa.farm.worker.model.EngineOutcomeKind
import software.medusa.farm.worker.model.EngineRunRequest
import software.medusa.farm.worker.model.PipelineInput
import software.medusa.farm.worker.model.PipelineStage
import software.medusa.farm.worker.model.PipelineStatus
import software.medusa.farm.worker.model.PostMergeInput
import software.medusa.farm.worker.model.PrState
import software.medusa.farm.worker.model.PublishResult
import software.medusa.farm.worker.model.WorkspaceRef

/**
 * The mutex-held half of the pipeline (prepare -> merge). Reference implementation skeleton: the
 * control flow mirrors the intended design; activity bodies and several waits are stubbed (TODO) —
 * see DESIGN.md §2.2. It is deterministic workflow code: no wall clock, no threads, no I/O except
 * through activity stubs. It HOLDS the repo mutex until it returns (at MERGED or FAILED), handing
 * post-merge work to a detached [PostMergeWorkflow] child.
 */
@Suppress("TooManyFunctions")
class BuildWorkflowImpl : BuildWorkflow {
  private val log = Workflow.getLogger(BuildWorkflowImpl::class.java)

  private val repo = Workflow.newActivityStub(RepoActivities::class.java, shortActivityOptions())
  private val github =
      Workflow.newActivityStub(GitHubActivities::class.java, shortActivityOptions())
  private val domain =
      Workflow.newActivityStub(DomainStoreActivities::class.java, shortActivityOptions())
  // `runEngine` is pinned to the dedicated `farm-engine` task queue so a saturated engine cannot
  // starve the light orchestration/GitHub/DB activities on `farm-pipeline` (DESIGN.md §2.4, §6.2).
  private val engine =
      Workflow.newActivityStub(EngineActivities::class.java, engineActivityOptions())

  // Signal-mutated state (single workflow thread; no locking needed).
  @Volatile private var aborted: Boolean = false
  @Volatile private var abortReason: String? = null
  @Volatile private var merged: Boolean = false
  @Volatile private var mergeCommitSha: String? = null
  @Volatile private var prClosed: Boolean = false
  @Volatile private var branchUpdated: Boolean = false
  @Volatile private var approved: Boolean = false
  private val checkUpdates = mutableListOf<CheckUpdate>()

  private var status = PipelineStatus(stage = PipelineStage.PREPARING, issueNumber = 0)

  override fun run(input: PipelineInput): BuildResult {
    transition(input, PipelineStage.PREPARING)
    val workspace = repo.prepareWorkspace(input.repo, input.issueNumber, input.engine)

    transition(input, PipelineStage.ENGINE_RUNNING)
    val outcome =
        engine.runEngine(
            EngineRunRequest(
                repo = input.repo,
                issueNumber = input.issueNumber,
                engine = input.engine,
                workspace = workspace,
                resumeSessionId = null,
            )
        )
    domain.recordSession(input.repo, input.issueNumber, outcome.sessionId, outcome.kind.name)
    if (outcome.kind == EngineOutcomeKind.FAILURE || outcome.kind == EngineOutcomeKind.NO_CHANGES) {
      return fail(input, outcome.failureSummary ?: "engine produced ${outcome.kind}")
    }

    transition(input, PipelineStage.PR_OPEN)
    val published = repo.publishBranchAndOpenPr(input.repo, input.issueNumber, workspace)
    if (!published.hadChanges || published.prNumber == null) {
      return fail(input, "no publishable changes")
    }
    status = status.copy(prNumber = published.prNumber, prUrl = published.prUrl)
    domain.upsertPipeline(status, input.repo)

    transition(input, PipelineStage.AWAITING_MERGE_CHECKS)
    if (!preMergeFixLoop(input, workspace, published)) {
      return fail(input, "pre-merge checks stayed red past the fix-loop cap")
    }
    if (aborted || prClosed) return failExternal(input)

    // Reactor stance (DESIGN.md §2.5): rebase onto latest trunk so drift re-runs checks; the
    // trunk-health gate then reads the LITERAL default-branch state before we land.
    repo.rebaseOntoTrunk(input.repo, workspace)
    if (!awaitTrunkHealthy(input)) {
      return fail(input, "trunk stayed unhealthy past the merge-gate cap")
    }

    armMerge(input, published)
    awaitMerge(input, published.prNumber)
    if (!merged) return failExternal(input)

    transition(input, PipelineStage.MERGED)
    val sha = mergeCommitSha ?: ""
    startDetachedPostMerge(input, sha, published)
    // Returning here is what releases the per-repo mutex the coordinator holds.
    return BuildResult(PipelineStage.MERGED, sha, published.prNumber, published.prUrl)
  }

  /**
   * Distinct heal loop #1 (DESIGN.md §6.6): while pre-merge checks are red, re-run the engine with
   * the current diff (incl. any human commits) + the failure, push the fix ADDITIVELY (never a
   * blind force-push — reactor stance), and re-await. Bounded. Returns true once green.
   */
  private fun preMergeFixLoop(
      input: PipelineInput,
      workspace: WorkspaceRef,
      published: PublishResult,
  ): Boolean {
    var attempts = 0
    while (!aborted && !prClosed) {
      when (awaitPreMergeChecks(input, published.prNumber ?: return false)) {
        CheckStatus.GREEN -> return true
        CheckStatus.RED -> {
          if (attempts >= maxFixAttempts) return false
          // TODO: feed engine the current diff + failing checks; then push additively.
          engine.runEngine(
              EngineRunRequest(
                  repo = input.repo,
                  issueNumber = input.issueNumber,
                  engine = input.engine,
                  workspace = workspace,
                  resumeSessionId = outcomeSessionHint(),
              )
          )
          repo.pushFix(input.repo, input.issueNumber, workspace)
          attempts++
        }
        else -> Unit // PENDING / NO_RUNS: keep awaiting on the next loop turn.
      }
    }
    return false
  }

  private fun awaitPreMergeChecks(input: PipelineInput, prNumber: Int): CheckStatus {
    // Prefer signalled CheckUpdates (webhook); a human push (onBranchUpdated) also wakes us so we
    // re-evaluate against the fresh branch; poll as a bounded backstop.
    val signalled =
        Workflow.await(pollInterval) {
          checkUpdates.isNotEmpty() || branchUpdated || aborted || prClosed
        }
    if (branchUpdated)
        branchUpdated = false // reactor stance: fold the human's push in, don't clobber.
    if (!signalled) {
      return github.getMergeCheckStatus(input.repo, "HEAD").status
    }
    if (checkUpdates.isEmpty()) return CheckStatus.PENDING
    return checkUpdates.removeAt(0).status
  }

  /**
   * The trunk-health merge gate (DESIGN.md §2.1–§2.2). Reads TRI-STATE health via the sole source
   * of truth [GitHubActivities.getTrunkHealth] (RED only after a flaky failure is re-run-confirmed;
   * PENDING while a tip run is in progress). RED (any author) / PENDING both wait — the build holds
   * the mutex, so a red trunk pauses the repo's merges. Bounded → escalate; admin break-glass
   * bypass.
   */
  private fun awaitTrunkHealthy(input: PipelineInput): Boolean {
    val deadline = Workflow.currentTimeMillis() + trunkHealthGraceMillis
    while (Workflow.currentTimeMillis() < deadline) {
      // Break-glass (DESIGN.md §2.2): an admin `approve` (per-PR "land this one anyway") bypasses
      // the
      // gate. Auditing + per-repo/global scopes live at the api; here it is the same signal path.
      if (approved) {
        log.warn("break-glass override for {}; merging past the trunk gate", input.repo.fullName)
        return true
      }
      when (github.getTrunkHealth(input.repo)) {
        // GREEN, or vacuously green (a tip with no relevant runs) — clear to merge.
        CheckStatus.GREEN,
        CheckStatus.NO_RUNS -> return true
        // RED (real, re-run-confirmed) = backpressure; PENDING = normal just after a merge. Both
        // wait.
        else -> Workflow.sleep(pollInterval)
      }
    }
    // TODO: escalate to a human rather than blocking the repo forever.
    log.warn("trunk stayed unhealthy for {}; escalating", input.repo.fullName)
    return false
  }

  /** Risk-gated auto-merge (DESIGN.md §2.4): auto when green; require `approve` when high-risk. */
  private fun armMerge(input: PipelineInput, published: PublishResult) {
    if (requiresHumanApproval()) {
      log.info("high-risk PR for {}; awaiting human approve", input.repo.fullName)
      Workflow.await { approved || aborted || prClosed }
    }
    published.prNumber?.let { github.armAutoMerge(input.repo, it) }
  }

  private fun awaitMerge(input: PipelineInput, prNumber: Int?) {
    if (prNumber == null) return
    while (!merged && !aborted && !prClosed) {
      if (!Workflow.await(pollInterval) { merged || aborted || prClosed }) {
        val pr = github.getPullRequestState(input.repo, prNumber)
        when (pr.state) {
          PrState.MERGED -> {
            merged = true
            mergeCommitSha = pr.mergeCommitSha
          }
          PrState.CLOSED_UNMERGED -> prClosed = true
          PrState.OPEN -> Unit // keep waiting
        }
      }
    }
  }

  private fun startDetachedPostMerge(
      input: PipelineInput,
      sha: String,
      published: PublishResult,
  ) {
    val child =
        Workflow.newChildWorkflowStub(
            PostMergeWorkflow::class.java,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId("postmerge:${input.repo.fullName}#${input.issueNumber}")
                .setTaskQueue(TaskQueues.pipeline)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                .build(),
        )
    // Fire-and-forget: the one honest ABANDON. Post-merge work outlives this build + the
    // coordinator's continue-as-new.
    io.temporal.workflow.Async.procedure(
        child::run,
        PostMergeInput(
            repo = input.repo,
            issueNumber = input.issueNumber,
            mergeCommitSha = sha,
            prUrl = published.prUrl,
        ),
    )
  }

  private fun fail(input: PipelineInput, summary: String): BuildResult {
    status = status.copy(stage = PipelineStage.FAILED, failureSummary = summary)
    transition(input, PipelineStage.FAILED)
    github.setPipelineLabel(input.repo, input.issueNumber, PipelineStage.FAILED.name)
    // Mutex releases even on failure (Flow wedged here; Farm does not) — the coordinator's circuit
    // breaker (DESIGN.md §6.6) is the only pause.
    return BuildResult(PipelineStage.FAILED)
  }

  private fun failExternal(input: PipelineInput): BuildResult =
      if (prClosed) fail(input, "PR closed by a human")
      else fail(input, "aborted: ${abortReason ?: "unspecified"}")

  private fun requiresHumanApproval(): Boolean {
    // TODO: high-risk when the diff touches infra needing an apply (esp. operator-only root infra),
    // or the repo has the per-repo "always review" override (DESIGN.md §2.4, §6.7). Stub: never.
    return false
  }

  private fun outcomeSessionHint(): String? = null // TODO: resume the prior engine session.

  private fun transition(input: PipelineInput, stage: PipelineStage) {
    status = status.copy(stage = stage, issueNumber = input.issueNumber)
    domain.recordStageTransition(input.repo, input.issueNumber, stage.name)
    domain.upsertPipeline(status, input.repo)
  }

  override fun approve() {
    approved = true
  }

  override fun abort(reason: String) {
    aborted = true
    abortReason = reason
  }

  override fun onCheckCompleted(update: CheckUpdate) {
    checkUpdates.add(update)
  }

  override fun onPrMerged(mergeCommitSha: String) {
    merged = true
    this.mergeCommitSha = mergeCommitSha
  }

  override fun onPrClosed() {
    prClosed = true
  }

  override fun onBranchUpdated() {
    // Reactor stance: a human pushed. Re-fetch + fold in on the next loop turn; never clobber.
    branchUpdated = true
  }

  override fun status(): PipelineStatus = status

  companion object {
    private val pollInterval: Duration = Duration.ofMinutes(3)
    private const val trunkHealthGraceMillis: Long = 30 * 60 * 1000
    private const val maxFixAttempts = 3

    /** Short, retriable side effects (git/GitHub/DB) on the `farm-pipeline` queue. */
    private fun shortActivityOptions(): ActivityOptions =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
            .build()

    /**
     * The long agent run: pinned to `farm-engine`; long start-to-close, short heartbeat, few
     * retries.
     */
    private fun engineActivityOptions(): ActivityOptions =
        ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.engine)
            .setStartToCloseTimeout(Duration.ofHours(2))
            .setHeartbeatTimeout(Duration.ofMinutes(2))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
            .build()
  }
}
