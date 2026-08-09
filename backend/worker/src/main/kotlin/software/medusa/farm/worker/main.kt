package software.medusa.farm.worker

import software.medusa.farm.shared.FarmStore

/** Entry point of the worker process. */
fun main() {
  val config = WorkerConfig.fromEnvironment()
  val store = FarmStore.buildWithoutMigrations(config.databaseUrl)
  Worker(config, store.fibonacci).run()
}
