package software.medusa.farm.worker.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.worker.activity.DeployActivities
import software.medusa.farm.worker.activity.DomainStoreActivities
import software.medusa.farm.worker.activity.GitHubActivities
import software.medusa.farm.worker.model.CheckStatus
import software.medusa.farm.worker.model.CheckUpdate
import software.medusa.farm.worker.model.PipelineStage
import software.medusa.farm.worker.model.PostMergeInput
import software.medusa.farm.worker.model.PostMergeStatus

/**
 * The detached, mutex-free half (DESIGN.md §2.3). Observes/awaits the trunk deploy for a merged
 * commit; green -> DONE, red -> the post-merge heal track (a NEW PR against trunk, bounded — merged
 * commits can't be force-pushed). It is the SELF-HEALER of Farm's own merge, NOT the authority for
 * trunk health: the build's merge gate reads the literal default-branch state, so this workflow
 * only decides whether ITS OWN merge needs healing, and self-heals only Farm-caused breakage by
 * default. Reference skeleton; bodies stubbed (TODO).
 */
class PostMergeWorkflowImpl : PostMergeWorkflow {
  private val log = Workflow.getLogger(PostMergeWorkflowImpl::class.java)

  private val deploy =
      Workflow.newActivityStub(DeployActivities::class.java, shortActivityOptions())
  private val github =
      Workflow.newActivityStub(GitHubActivities::class.java, shortActivityOptions())
  private val domain =
      Workflow.newActivityStub(DomainStoreActivities::class.java, shortActivityOptions())

  @Volatile private var approved: Boolean = false
  private val checkUpdates = mutableListOf<CheckUpdate>()
  private var state = PostMergeStatus(stage = PipelineStage.POST_MERGE_CHECKS)

  override fun run(input: PostMergeInput) {
    record(input, PipelineStage.POST_MERGE_CHECKS)
    // Trigger-or-observe (DESIGN.md §6.7): a merge to trunk auto-fires apply-* on push, so this is
    // mostly observe; it dispatches only for deploys needing an explicit workflow_dispatch.
    deploy.triggerDeploy(input.repo, input.mergeCommitSha)

    when (awaitTrunkDeploy(input)) {
      CheckStatus.RED -> selfHeal(input)
      else -> Unit // GREEN / vacuous-green backstop
    }

    record(input, PipelineStage.DONE)
    github.markIssueDone(input.repo, input.issueNumber, input.prUrl)
  }

  private fun awaitTrunkDeploy(input: PostMergeInput): CheckStatus {
    val sha = input.mergeCommitSha
    val deadline = Workflow.currentTimeMillis() + postMergeGraceMillis
    while (Workflow.currentTimeMillis() < deadline) {
      when (deploy.getDeployStatus(input.repo, sha).status) {
        CheckStatus.GREEN -> return CheckStatus.GREEN
        CheckStatus.RED -> return CheckStatus.RED
        else -> Workflow.await(pollInterval) { checkUpdates.any { it.commitSha == sha } }
      }
    }
    return CheckStatus.GREEN // no-runs-past-grace => vacuous green, matching Flow's backstop
  }

  /**
   * Distinct heal loop #2 (DESIGN.md §6.6): trunk red AFTER merge -> open a NEW PR against trunk
   * (bounded). A fix touching operator-only root infra is authored + escalated, not applied
   * (DESIGN.md §6.7). Farm self-heals only its own merge's breakage by default; a human-caused red
   * trunk is held+escalated, not competed with.
   */
  private fun selfHeal(input: PostMergeInput) {
    record(input, PipelineStage.SELF_HEALING)
    state = state.copy(healAttempts = state.healAttempts + 1)
    if (state.healAttempts > maxHealAttempts) {
      log.warn(
          "self-heal cap hit for {} @ {}; escalating",
          input.repo.fullName,
          input.mergeCommitSha,
      )
      return
    }
    // Risk-gated (DESIGN.md §2.4/§6.7): a high-risk heal PR (infra apply, esp. operator-only root
    // infra) waits for a human `approve` before we land it. Stub: not high-risk, so no wait.
    if (requiresHumanApproval()) {
      Workflow.await { approved }
    }
    // TODO: open a NEW fixing PR against trunk (a fresh BuildWorkflow-style flow), await its merge,
    // re-observe trunk; recurse up to the cap. Merged commits can't be force-pushed, hence a new
    // PR.
    log.warn("self-heal PR needed for {} after merge {}", input.repo.fullName, input.mergeCommitSha)
  }

  private fun requiresHumanApproval(): Boolean {
    // TODO: high-risk when the fix touches infra needing an apply (esp. operator-only root infra).
    return false
  }

  private fun record(input: PostMergeInput, stage: PipelineStage) {
    state = state.copy(stage = stage)
    domain.recordStageTransition(input.repo, input.issueNumber, stage.name)
  }

  override fun approve() {
    approved = true
  }

  override fun onCheckCompleted(update: CheckUpdate) {
    checkUpdates.add(update)
  }

  override fun status(): PostMergeStatus = state

  companion object {
    private val pollInterval: Duration = Duration.ofMinutes(3)
    private const val postMergeGraceMillis: Long = 15 * 60 * 1000
    private const val maxHealAttempts = 3

    private fun shortActivityOptions(): ActivityOptions =
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
            .build()
  }
}
