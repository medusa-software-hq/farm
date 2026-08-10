package software.medusa.farm.shared

import java.math.BigInteger
import java.util.concurrent.ConcurrentSkipListMap

/** In-memory [FibonacciStore]. */
class InMemoryFibonacciStore : FibonacciStore {
  private val values = ConcurrentSkipListMap<Int, BigInteger>()

  override suspend fun list(): List<FibonacciEntry> =
      values.entries.map { FibonacciEntry(it.key, it.value) }

  override suspend fun highestIndex(): Int? = values.lastEntry()?.key

  override suspend fun record(index: Int, value: BigInteger) {
    values[index] = value
  }
}
