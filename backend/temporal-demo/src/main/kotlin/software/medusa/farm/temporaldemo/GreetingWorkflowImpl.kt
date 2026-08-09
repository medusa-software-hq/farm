package software.medusa.farm.temporaldemo

class GreetingWorkflowImpl : GreetingWorkflow {
  override fun greet(name: String): String = "Hello, $name — from Temporal Cloud."
}
