package software.medusa.farm.shared

import java.math.BigInteger

/** The stored Fibonacci sequence. */
interface FibonacciStore {
  suspend fun list(): List<FibonacciEntry>

  suspend fun highestIndex(): Int?

  suspend fun record(index: Int, value: BigInteger)
}
