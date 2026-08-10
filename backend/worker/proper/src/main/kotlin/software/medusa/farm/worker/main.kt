package software.medusa.farm.worker

/** Env-configured entry point of the worker process. */
fun main() {
  runFarmWorker(WorkerConfig.fromEnvironment())
}
