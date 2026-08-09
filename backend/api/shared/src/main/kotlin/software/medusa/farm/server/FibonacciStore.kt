package software.medusa.farm.server

class FibonacciEntry(
    val index: Int,
    val value: String,
)

interface FibonacciStore {
  suspend fun list(): List<FibonacciEntry>
}
