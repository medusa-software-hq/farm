package software.medusa.farm.worker

import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import software.medusa.farm.shared.FibonacciStore

/**
 * Runs the store's `suspend` funs on the activity thread. Activities may block, so bridging with
 * [runBlocking] here is fine — the constraint that forbids blocking lives in the workflow.
 */
class FibonacciActivitiesImpl(private val store: FibonacciStore) : FibonacciActivities {
  override fun highestIndex(): Int? = runBlocking { store.highestIndex() }

  override fun store(index: Int, value: String) = runBlocking {
    store.record(index, BigInteger(value))
  }
}
