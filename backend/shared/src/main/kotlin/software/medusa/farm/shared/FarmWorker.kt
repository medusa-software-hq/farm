package software.medusa.farm.shared

/** The farm's single Temporal worker: one task queue, shared by every workflow type. */
object FarmWorker {
  const val TASK_QUEUE = "farm-tasks"
}
