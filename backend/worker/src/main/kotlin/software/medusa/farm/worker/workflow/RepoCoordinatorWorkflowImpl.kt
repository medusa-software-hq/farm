package software.medusa.farm.worker.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.worker.activity.GitHubActivities
import software.medusa.farm.worker.model.PipelineInput
import software.medusa.farm.worker.model.PipelineStage
import software.medusa.farm.worker.model.RepoCoordinatorStatus
import software.medusa.farm.worker.model.RepoRef

/**
 * The per-repo mutex holder. Single running execution per repo (workflow id `repo:<full-name>`).
 * Picks one unblocked ready issue at a time and starts an **owned** child [BuildWorkflow] (no
 * ABANDON), then blocks in the child's synchronous `run(...)` call — that await IS the mutex, and
 * it returns when the build merges (or fails). Post-merge work runs on in a detached
 * PostMergeWorkflow the build spawns, so the coordinator can move to the next issue.
 * Continue-as-new (between picks, with no child in flight) bounds history. See DESIGN.md §2.1 and
 * section B.
 */
class RepoCoordinatorWorkflowImpl : RepoCoordinatorWorkflow {
  private val log = Workflow.getLogger(RepoCoordinatorWorkflowImpl::class.java)
  private val github =
      Workflow.newActivityStub(
          GitHubActivities::class.java,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
              .build(),
      )

  @Volatile private var wakeUp: Boolean = false
  @Volatile private var approved: Boolean = false
  @Volatile private var activeIssue: Int? = null
  @Volatile private var trunkHealthy: Boolean = true
  private var processedCount: Int = 0
  private var consecutiveFailures: Int = 0

  override fun coordinate(repo: RepoRef) {
    var iterations = 0
    while (iterations < maxIterationsBeforeContinueAsNew) {
      // Circuit breaker (DESIGN.md §6.6): after K straight failures, pause re-pick and require a
      // human `approve` — a soft escalation, not Flow's wedge-on-every-failure default.
      if (consecutiveFailures >= circuitBreakerThreshold) {
        log.warn(
            "circuit breaker open for {} after {} failures",
            repo.fullName,
            consecutiveFailures,
        )
        Workflow.await { approved }
        approved = false
        consecutiveFailures = 0
      }

      val next = github.discoverNextReadyIssue(repo)
      if (next != null) {
        activeIssue = next.issueNumber
        // OWNED child, awaited synchronously: this call is the mutex. It returns when the build
        // reaches MERGED (having handed off a detached PostMergeWorkflow) or FAILED.
        val child =
            Workflow.newChildWorkflowStub(
                BuildWorkflow::class.java,
                ChildWorkflowOptions.newBuilder()
                    .setWorkflowId("pipeline:${repo.fullName}#${next.issueNumber}")
                    .build(),
            )
        val result =
            child.run(
                PipelineInput(repo = repo, issueNumber = next.issueNumber, engine = defaultEngine)
            )
        consecutiveFailures =
            if (result.stage == PipelineStage.FAILED) consecutiveFailures + 1 else 0
        activeIssue = null
        processedCount++
        iterations++
      } else {
        // Idle: sleep until a wake signal or the periodic poll timer, then re-check.
        Workflow.await(idlePollInterval) { wakeUp }
        wakeUp = false
      }
    }
    log.info("continue-as-new for {} after {} builds", repo.fullName, processedCount)
    Workflow.continueAsNew(repo)
  }

  override fun onReadyIssuesChanged() {
    wakeUp = true
  }

  override fun approve() {
    approved = true
  }

  override fun onTrunkStatusChanged(sha: String, healthy: Boolean) {
    // Authoritative per-repo trunk health; relayed to the active build's trunk-health merge gate.
    // TODO: forward `healthy` to the in-flight owned BuildWorkflow (or expose via getTrunkHealth).
    trunkHealthy = healthy
  }

  override fun status(): RepoCoordinatorStatus =
      RepoCoordinatorStatus(
          repo = RepoRef("", ""), // TODO: carry repo in workflow state for the query
          activeIssue = activeIssue,
          processedCount = processedCount,
          trunkHealthy = trunkHealthy,
      )

  companion object {
    private const val defaultEngine = "claude"
    private const val maxIterationsBeforeContinueAsNew = 50
    private const val circuitBreakerThreshold = 3
    private val idlePollInterval: Duration = Duration.ofMinutes(5)
  }
}
