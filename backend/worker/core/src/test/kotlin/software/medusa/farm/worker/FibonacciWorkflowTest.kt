package software.medusa.farm.worker

import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import software.medusa.farm.shared.FibonacciEntry
import software.medusa.farm.shared.InMemoryFibonacciStore

/**
 * Exercises the workflow + activities against Temporal's in-memory test server — never the real
 * cloud.
 */
class FibonacciWorkflowTest {
  private val env = TestWorkflowEnvironment.newInstance()
  private val store = InMemoryFibonacciStore()

  private fun startWorker() {
    val worker = env.newWorker(TemporalWorkerHost.TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(FibonacciWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(FibonacciActivitiesImpl(store))
    env.start()
  }

  private fun newWorkflow(): FibonacciWorkflow =
      env.workflowClient.newWorkflowStub(
          FibonacciWorkflow::class.java,
          WorkflowOptions.newBuilder().setTaskQueue(TemporalWorkerHost.TASK_QUEUE).build(),
      )

  @AfterTest fun tearDown() = env.close()

  @Test
  fun `stores the sequence through n`() {
    startWorker()
    newWorkflow().computeThrough(10)

    val stored = runBlocking { store.list() }
    assertEquals((0..10).toList(), stored.map(FibonacciEntry::index))
    // fib(10) = 55.
    assertEquals(55, stored.last().value.toInt())
  }

  @Test
  fun `resumes from stored progress without rewriting`() {
    runBlocking { store.record(0, java.math.BigInteger.ZERO) }
    startWorker()
    // A fresh stub each call; the second resumes from the highest stored index.
    newWorkflow().computeThrough(5)
    newWorkflow().computeThrough(8)

    val stored = runBlocking { store.list() }
    assertEquals((0..8).toList(), stored.map(FibonacciEntry::index))
    // fib(8) = 21.
    assertEquals(21, stored.last().value.toInt())
  }
}
