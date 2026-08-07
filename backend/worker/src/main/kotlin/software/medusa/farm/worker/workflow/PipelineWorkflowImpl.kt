package software.medusa.farm.worker.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.worker.activity.DeployActivities
import software.medusa.farm.worker.activity.DomainStoreActivities
import software.medusa.farm.worker.activity.EngineActivities
import software.medusa.farm.worker.activity.GitHubActivities
import software.medusa.farm.worker.activity.RepoActivities
import software.medusa.farm.worker.model.CheckStatus
import software.medusa.farm.worker.model.CheckUpdate
import software.medusa.farm.worker.model.EngineOutcomeKind
import software.medusa.farm.worker.model.EngineRunRequest
import software.medusa.farm.worker.model.PipelineInput
import software.medusa.farm.worker.model.PipelineResult
import software.medusa.farm.worker.model.PipelineStage
import software.medusa.farm.worker.model.PipelineStatus
import software.medusa.farm.worker.model.PrState

/**
 * Reference implementation skeleton. The control flow mirrors the intended design; activity bodies
 * and several waits are stubbed (TODO) — see DESIGN.md. It is deterministic workflow code: no wall
 * clock, no threads, no I/O except through activity stubs.
 */
@Suppress("TooManyFunctions")
class PipelineWorkflowImpl : PipelineWorkflow {
  private val log = Workflow.getLogger(PipelineWorkflowImpl::class.java)

  private val repo = Workflow.newActivityStub(RepoActivities::class.java, shortActivityOptions())
  private val github =
      Workflow.newActivityStub(GitHubActivities::class.java, shortActivityOptions())
  private val deploy =
      Workflow.newActivityStub(DeployActivities::class.java, shortActivityOptions())
  private val domain =
      Workflow.newActivityStub(DomainStoreActivities::class.java, shortActivityOptions())
  private val engine =
      Workflow.newActivityStub(EngineActivities::class.java, engineActivityOptions())

  // Signal-mutated state (single workflow thread; no locking needed).
  @Volatile private var aborted: Boolean = false
  @Volatile private var abortReason: String? = null
  @Volatile private var merged: Boolean = false
  @Volatile private var mergeCommitSha: String? = null
  private val checkUpdates = mutableListOf<CheckUpdate>()

  private var status = PipelineStatus(stage = PipelineStage.PREPARING, issueNumber = 0)

  override fun run(input: PipelineInput): PipelineResult {
    transition(input.repo, input.issueNumber, PipelineStage.PREPARING)
    val workspace = repo.prepareWorkspace(input.repo, input.issueNumber, input.engine)

    transition(input.repo, input.issueNumber, PipelineStage.ENGINE_RUNNING)
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

    transition(input.repo, input.issueNumber, PipelineStage.PR_OPEN)
    val published = repo.publishBranchAndOpenPr(input.repo, input.issueNumber, workspace)
    if (!published.hadChanges || published.prNumber == null) {
      return fail(input, "no publishable changes")
    }
    status = status.copy(prNumber = published.prNumber, prUrl = published.prUrl)
    domain.upsertPipeline(status, input.repo)
    github.armAutoMerge(input.repo, published.prNumber)

    transition(input.repo, input.issueNumber, PipelineStage.AWAITING_MERGE_CHECKS)
    awaitMerge(input, published.prNumber)

    // Mutex releases at merge: tell the coordinator so it can pick the next issue while this
    // pipeline keeps watching post-merge checks and self-heals below.
    transition(input.repo, input.issueNumber, PipelineStage.MERGED)
    notifyCoordinatorMutexReleased(input)

    transition(input.repo, input.issueNumber, PipelineStage.POST_MERGE_CHECKS)
    val postMerge = awaitPostMergeChecks(input)
    if (postMerge == CheckStatus.RED) {
      selfHeal(input)
    }

    transition(input.repo, input.issueNumber, PipelineStage.DONE)
    github.markIssueDone(input.repo, input.issueNumber, published.prUrl)
    repo.cleanupWorkspace(workspace)
    return PipelineResult(PipelineStage.DONE, published.prUrl)
  }

  private fun awaitMerge(input: PipelineInput, prNumber: Int) {
    // Prefer the merge signal (webhook); fall back to a bounded poll timer if no signal arrives.
    while (!merged && !aborted) {
      val signalled = Workflow.await(pollInterval) { merged || aborted }
      if (!signalled) {
        val pr = github.getPullRequestState(input.repo, prNumber)
        when (pr.state) {
          PrState.MERGED -> {
            merged = true
            mergeCommitSha = pr.mergeCommitSha
          }
          PrState.CLOSED_UNMERGED -> {
            fail(input, "PR closed without merge")
            return
          }
          PrState.OPEN -> Unit // keep waiting
        }
      }
    }
    checkAbort(input)
  }

  private fun awaitPostMergeChecks(input: PipelineInput): CheckStatus {
    val sha = mergeCommitSha ?: return CheckStatus.NO_RUNS
    deploy.triggerDeploy(input.repo, sha)
    // TODO: consume signalled CheckUpdates first; poll deploy status as a backstop.
    val deadline = Workflow.currentTimeMillis() + postMergeGraceMillis
    while (Workflow.currentTimeMillis() < deadline) {
      val snap = deploy.getDeployStatus(input.repo, sha)
      when (snap.status) {
        CheckStatus.GREEN -> return CheckStatus.GREEN
        CheckStatus.RED -> return CheckStatus.RED
        else -> Workflow.await(pollInterval) { checkUpdates.any { it.commitSha == sha } }
      }
    }
    return CheckStatus.GREEN // no-runs-past-grace => vacuous green, matching Flow's backstop
  }

  private fun selfHeal(input: PipelineInput) {
    transition(input.repo, input.issueNumber, PipelineStage.SELF_HEALING)
    // Treat the broken trunk as a fresh task: start a child pipeline dedicated to the fix.
    // TODO: create/annotate a heal issue via GitHubActivities, then run the child on it.
    log.warn("self-heal triggered for {} after merge {}", input.repo.fullName, mergeCommitSha)
  }

  private fun notifyCoordinatorMutexReleased(input: PipelineInput) {
    val coordinatorId = "repo:${input.repo.fullName}"
    val coordinator =
        Workflow.newExternalWorkflowStub(RepoCoordinatorWorkflow::class.java, coordinatorId)
    coordinator.onPipelineMutexReleased(input.issueNumber)
  }

  private fun fail(input: PipelineInput, summary: String): PipelineResult {
    status = status.copy(stage = PipelineStage.FAILED, failureSummary = summary)
    transition(input.repo, input.issueNumber, PipelineStage.FAILED)
    github.setPipelineLabel(input.repo, input.issueNumber, PipelineStage.FAILED.name)
    // Release the mutex even on failure so the repo is not wedged (Flow blocked here; Farm heals).
    notifyCoordinatorMutexReleased(input)
    return PipelineResult(PipelineStage.FAILED)
  }

  private fun checkAbort(input: PipelineInput) {
    if (aborted) {
      fail(input, "aborted: ${abortReason ?: "unspecified"}")
    }
  }

  private fun transition(
      repoRef: software.medusa.farm.worker.model.RepoRef,
      issueNumber: Int,
      stage: PipelineStage,
  ) {
    status = status.copy(stage = stage, issueNumber = issueNumber)
    domain.recordStageTransition(repoRef, issueNumber, stage.name)
    domain.upsertPipeline(status, repoRef)
  }

  override fun approve() {
    log.info("approve signal received")
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

  override fun status(): PipelineStatus = status

  companion object {
    private val pollInterval: Duration = Duration.ofMinutes(3)
    private const val postMergeGraceMillis: Long = 15 * 60 * 1000

    /** Short, retriable side effects (git/GitHub/DB). */
    private fun shortActivityOptions(): ActivityOptions =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
            .build()

    /** The long agent run: generous start-to-close, short heartbeat, few retries. */
    private fun engineActivityOptions(): ActivityOptions =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofHours(2))
            .setHeartbeatTimeout(Duration.ofMinutes(2))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
            .build()
  }
}
