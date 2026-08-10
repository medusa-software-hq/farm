package software.medusa.farm.worker

import software.medusa.farm.shared.FarmStore

/** Runs the worker logic against the database configured in [config]. */
fun runFarmWorker(config: WorkerConfig) {
  val store = FarmStore.buildWithoutMigrations(config.databaseUrl)
  Worker(config, store.fibonacci).run()
}
