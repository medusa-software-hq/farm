package software.medusa.farm.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.shared.db.FarmDatabase

/** [CounterStore] backed by a Postgres database. */
class PostgresCounterStore(
    private val database: FarmDatabase,
) : CounterStore {
  override suspend fun getCount(counterId: CounterId): Int =
      withContext(Dispatchers.IO) {
        database.counterQueries.selectCount(counterId.id).executeAsOneOrNull() ?: 0
      }

  override suspend fun incrementAndGetCount(counterId: CounterId): Int = adjust(counterId, +1)

  override suspend fun decrementAndGetCount(counterId: CounterId): Int = adjust(counterId, -1)

  private suspend fun adjust(counterId: CounterId, delta: Int): Int =
      withContext(Dispatchers.IO) {
        database.counterQueries.adjustCount(counterId.id, delta).executeAsOne()
      }
}
