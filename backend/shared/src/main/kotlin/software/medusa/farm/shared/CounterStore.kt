package software.medusa.farm.shared

interface CounterStore {
  suspend fun getCount(counterId: CounterId): Int

  suspend fun incrementAndGetCount(counterId: CounterId): Int

  suspend fun decrementAndGetCount(counterId: CounterId): Int
}
