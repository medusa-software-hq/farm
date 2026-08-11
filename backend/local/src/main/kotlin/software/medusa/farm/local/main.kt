package software.medusa.farm.local

import org.slf4j.LoggerFactory
import software.medusa.farm.server.NoOpAuthDecorator
import software.medusa.farm.server.TemporalFibonacciStarter
import software.medusa.farm.server.buildServer
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.InMemoryCounterStore
import software.medusa.farm.shared.InMemoryFibonacciStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.worker.TemporalWorkerHost

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

private const val localTemporalAddress = "localhost:7233"
private const val localTemporalNamespace = "default"

private val logger = LoggerFactory.getLogger("software.medusa.farm.local.Main")

/**
 * The one-process local stack: the API and an in-process Fibonacci worker over one shared in-memory
 * [FarmStore].
 *
 * Tolerant of an absent local Temporal server: if the worker can't connect, log it and keep serving
 * — only starting a workflow degrades.
 */
fun main() {
  val farmStore =
      FarmStore(InMemoryCounterStore(), InMemoryFibonacciStore(), InMemoryLinkedOrgStore())

  try {
    TemporalWorkerHost(
            address = localTemporalAddress,
            namespace = localTemporalNamespace,
            authConfig = WorkflowServiceAuthConfig.Local,
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
                  WorkflowServiceAuthConfig.Local,
              ),
          gitHubOrgs = null,
      )
      .start()
      .join()
}
