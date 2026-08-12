package software.medusa.farm.shared

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Computes and persists the Fibonacci sequence through a given index on Temporal Cloud. Shared so
 * the API's typed start stub and the worker's registration derive the workflow type from the same
 * interface — the type name cannot drift.
 */
@WorkflowInterface
interface FibonacciWorkflow {
  @WorkflowMethod fun computeThrough(n: Int)
}
