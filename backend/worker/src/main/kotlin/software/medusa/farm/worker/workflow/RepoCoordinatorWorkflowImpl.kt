package software.medusa.farm.worker.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.ParentClosePolicy
import io.temporal.common.RetryOptions
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import software.medusa.farm.worker.activity.GitHubActivities
import software.medusa.farm.worker.model.PipelineInput
import software.medusa.farm.worker.model.RepoCoordinatorStatus
import software.medusa.farm.worker.model.RepoRef

/**
 * The per-repo mutex holder. Single running execution per repo (workflow id `repo:<full-name>`).
 * Picks one ready issue at a time, starts a child [PipelineWorkflow] with `ABANDON` parent-close
 * policy (so post-merge/self-heal outlive the coordinator's continue-as-new), then blocks until
 * that pipeline signals [onPipelineMutexReleased] at MERGED. Continue-as-new bounds history.
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
  @Volatile private var mutexReleasedFor: Int? = null
  @Volatile private var activeIssue: Int? = null
  private var processedCount: Int = 0

  override fun coordinate(repo: RepoRef) {
    var iterations = 0
    while (iterations < maxIterationsBeforeContinueAsNew) {
      val next = github.discoverNextReadyIssue(repo)
      if (next != null) {
        activeIssue = next.issueNumber
        mutexReleasedFor = null
        val child =
            Workflow.newChildWorkflowStub(
                PipelineWorkflow::class.java,
                ChildWorkflowOptions.newBuilder()
                    .setWorkflowId("pipeline:${repo.fullName}#${next.issueNumber}")
                    .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                    .build(),
            )
        // Start async and detach: we only block until the pipeline releases the mutex (at MERGED),
        // not until it fully finishes (post-merge checks + self-heal run on in the abandoned
        // child).
        Async.function(
            child::run,
            PipelineInput(repo = repo, issueNumber = next.issueNumber, engine = defaultEngine),
        )
        Workflow.await { mutexReleasedFor == next.issueNumber }
        activeIssue = null
        processedCount++
        iterations++
      } else {
        // Idle: sleep until a wake signal or the periodic poll timer, then re-check.
        Workflow.await(idlePollInterval) { wakeUp }
        wakeUp = false
      }
    }
    log.info("continue-as-new for {} after {} pipelines", repo.fullName, processedCount)
    Workflow.continueAsNew(repo)
  }

  override fun onReadyIssuesChanged() {
    wakeUp = true
  }

  override fun onPipelineMutexReleased(issueNumber: Int) {
    mutexReleasedFor = issueNumber
  }

  override fun status(): RepoCoordinatorStatus =
      RepoCoordinatorStatus(
          repo = RepoRef("", ""), // TODO: carry repo in workflow state for the query
          activeIssue = activeIssue,
          processedCount = processedCount,
      )

  companion object {
    private const val defaultEngine = "claude"
    private const val maxIterationsBeforeContinueAsNew = 50
    private val idlePollInterval: Duration = Duration.ofMinutes(5)
  }
}
