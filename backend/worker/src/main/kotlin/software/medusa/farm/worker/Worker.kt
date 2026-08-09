package software.medusa.farm.worker

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Persists the computed sequence, resuming from stored progress so a restart continues rather than
 * repeats.
 */
class Worker(private val config: WorkerConfig, private val store: FibonacciStore) {
  private val log = LoggerFactory.getLogger(Worker::class.java)

  fun run() = runBlocking {
    val highest = store.highestIndex()
    log.info("starting: highest stored index = {}, computing up to {}", highest, config.maxIndex)

    for ((index, value) in fibonacci().takeWhile { (index, _) -> index <= config.maxIndex }) {
      // Already stored — advance the in-memory state without re-persisting.
      if (highest != null && index <= highest) continue
      store.record(index, value)
      log.info("fib({}) = {}", index, value)
      delay(config.tick)
    }

    log.info("done: Fibonacci stored through index {}", config.maxIndex)
  }
}
