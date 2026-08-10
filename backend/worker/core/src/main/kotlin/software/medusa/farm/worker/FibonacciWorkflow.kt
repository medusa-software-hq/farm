package software.medusa.farm.worker

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/** Computes and persists the Fibonacci sequence through a given index on Temporal Cloud. */
@WorkflowInterface
interface FibonacciWorkflow {
  @WorkflowMethod fun computeThrough(n: Int)
}
