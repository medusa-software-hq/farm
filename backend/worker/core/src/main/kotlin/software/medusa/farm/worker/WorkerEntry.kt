package software.medusa.farm.worker

import software.medusa.farm.shared.FarmStore

/** Runs the Fibonacci Temporal worker over [config] and blocks, staying up to process tasks. */
fun runTemporalWorker(config: WorkerConfig) {
  val store = FarmStore.buildWithoutMigrations(config.databaseUrl)
  TemporalWorkerHost(
          config.temporalAddress,
          config.temporalNamespace,
          config.temporalAuth,
          store.fibonacci,
      )
      .start()
  // Stay up; the worker factory polls on background threads.
  Thread.currentThread().join()
}
