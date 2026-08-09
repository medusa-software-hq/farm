package software.medusa.farm.server

// The local server has no worker writing rows, so there is nothing to read back.
class InMemoryFibonacciStore : FibonacciStore {
  override suspend fun list(): List<FibonacciEntry> = emptyList()
}
