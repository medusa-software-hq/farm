package software.medusa.farm.worker

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Resumes from the highest stored index, then computes the sequence deterministically (in-workflow,
 * from [fibonacci]) and persists each new index through `n` via the activity. The computation is
 * pure and replay-safe; only the store reads/writes are activities.
 */
class FibonacciWorkflowImpl : FibonacciWorkflow {
  private val activities =
      Workflow.newActivityStub(
          FibonacciActivities::class.java,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
      )

  override fun computeThrough(n: Int) {
    val highest = activities.highestIndex()
    for ((index, value) in fibonacci().takeWhile { (index, _) -> index <= n }) {
      // Already persisted on an earlier run — skip without re-writing.
      if (highest != null && index <= highest) continue
      activities.store(index, value.toString())
    }
  }
}
