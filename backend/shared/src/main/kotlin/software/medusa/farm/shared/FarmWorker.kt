package software.medusa.farm.shared

/** The farm's Temporal worker: one task queue, shared by every workflow type. */
object FarmWorker {
  /** The queue a deployment uses unless it is told otherwise. */
  const val DEFAULT_TASK_QUEUE = "farm-tasks"

  /**
   * The queue this process works on. Overridable so that a run which is not the deployment — an
   * ephemeral one under test — takes only its own work, rather than competing for the deployment's
   * from the same namespace.
   *
   * Every process that starts a workflow has to agree on this, or work is queued where nothing is
   * listening.
   */
  fun taskQueueFrom(env: Map<String, String> = System.getenv()): String =
      env["FARM_TASK_QUEUE"] ?: DEFAULT_TASK_QUEUE
}
