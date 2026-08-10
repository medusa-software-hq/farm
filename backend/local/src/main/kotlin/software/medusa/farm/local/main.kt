package software.medusa.farm.local

import org.slf4j.LoggerFactory
import software.medusa.farm.server.NoOpAuthDecorator
import software.medusa.farm.server.TemporalFibonacciStarter
import software.medusa.farm.server.buildServer
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.InMemoryCounterStore
import software.medusa.farm.shared.InMemoryFibonacciStore
import software.medusa.farm.worker.TemporalWorkerHost

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

// The local Temporal dev server (`temporal server start-dev`): plaintext, no API key.
private const val localTemporalAddress = "localhost:7233"
private const val localTemporalNamespace = "default"

private val logger = LoggerFactory.getLogger("software.medusa.farm.local.Main")

/**
 * The one-process local stack: one in-memory [FarmStore], a Fibonacci worker hosted in-process over
 * it, and the API served over the same store — so StartFibonacci flows through the local Temporal
 * dev server to the in-process worker and back out via ListFibonacci.
 *
 * The dev server is optional: if the worker can't reach Temporal, log and keep serving the API (the
 * counter and ListFibonacci still work; StartFibonacci degrades) — the same tolerance the Cloud API
 * has for a missing Temporal key.
 */
fun main() {
  val farmStore = FarmStore(InMemoryCounterStore(), InMemoryFibonacciStore())

  try {
    TemporalWorkerHost(
            address = localTemporalAddress,
            namespace = localTemporalNamespace,
            apiKey = null,
            store = farmStore.fibonacci,
        )
        .start()
  } catch (e: Exception) {
    logger.warn(
        "Temporal dev server unreachable at {}; serving the API without a worker " +
            "(StartFibonacci will degrade). Start it with `temporal server start-dev`.",
        localTemporalAddress,
        e,
    )
  }

  buildServer(
          originRegex = localCorsOriginRegex,
          port = localPort,
          auth = NoOpAuthDecorator,
          farmStore = farmStore,
          fibonacciStarter =
              TemporalFibonacciStarter(
                  localTemporalAddress,
                  localTemporalNamespace,
                  apiKey = null,
              ),
      )
      .start()
      .join()
}
