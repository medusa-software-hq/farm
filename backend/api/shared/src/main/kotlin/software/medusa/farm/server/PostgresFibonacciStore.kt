package software.medusa.farm.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.db.FarmDatabase

class PostgresFibonacciStore(
    private val database: FarmDatabase,
) : FibonacciStore {
  override suspend fun list(): List<FibonacciEntry> =
      withContext(Dispatchers.IO) {
        database.fibonacciQueries.selectAll().executeAsList().map { FibonacciEntry(it.n, it.value_) }
      }
}
