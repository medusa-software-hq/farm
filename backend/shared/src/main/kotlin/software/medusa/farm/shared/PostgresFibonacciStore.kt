package software.medusa.farm.shared

import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.shared.db.FarmDatabase

/** [FibonacciStore] backed by a Postgres database. */
class PostgresFibonacciStore(
    private val database: FarmDatabase,
) : FibonacciStore {
  override suspend fun list(): List<FibonacciEntry> =
      withContext(Dispatchers.IO) {
        database.fibonacciQueries.selectAll().executeAsList().map {
          FibonacciEntry(it.n, BigInteger(it.value_))
        }
      }

  override suspend fun highestIndex(): Int? =
      withContext(Dispatchers.IO) { database.fibonacciQueries.highestIndex().executeAsOneOrNull() }

  override suspend fun record(index: Int, value: BigInteger) {
    withContext(Dispatchers.IO) { database.fibonacciQueries.record(index, value.toString()) }
  }
}
