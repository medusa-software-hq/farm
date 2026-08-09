package software.medusa.farm.server

import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.InMemoryCounterStore
import software.medusa.farm.shared.InMemoryFibonacciStore

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

fun main() {
  buildServer(
          originRegex = localCorsOriginRegex,
          port = localPort,
          auth = NoOpAuthDecorator,
          farmStore = FarmStore(InMemoryCounterStore(), InMemoryFibonacciStore()),
      )
      .start()
      .join()
}
