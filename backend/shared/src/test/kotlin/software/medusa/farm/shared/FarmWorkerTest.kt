package software.medusa.farm.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class FarmWorkerTest {
  @Test
  fun `a deployment that says nothing works the default queue`() {
    assertEquals(FarmWorker.DEFAULT_TASK_QUEUE, FarmWorker.taskQueueFrom(env = emptyMap()))
  }

  @Test
  fun `a run can be given a queue of its own`() {
    // The name is the contract between every process that starts a workflow and the worker that
    // takes it; they read it from the same place or the work goes somewhere nothing is listening.
    assertEquals(
        "farm-tasks-ephemeral-42",
        FarmWorker.taskQueueFrom(env = mapOf("FARM_TASK_QUEUE" to "farm-tasks-ephemeral-42")),
    )
  }
}
