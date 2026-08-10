package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * The database I/O the [FibonacciWorkflow] delegates to (kept out of the deterministic workflow).
 */
@ActivityInterface
interface FibonacciActivities {
  @ActivityMethod fun highestIndex(): Int?

  /** Persists `value` (decimal string, matching the TEXT column) at `index`. */
  @ActivityMethod fun store(index: Int, value: String)
}
