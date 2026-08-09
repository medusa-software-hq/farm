package software.medusa.farm.temporaldemo

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/** The tiniest possible workflow — enough to prove a round-trip through Temporal Cloud. */
@WorkflowInterface
interface GreetingWorkflow {
  @WorkflowMethod fun greet(name: String): String
}
