package software.medusa.farm.worker

/** Entry point of the worker process. */
fun main() {
  val config = WorkerConfig.fromEnvironment()
  val store = FibonacciStore.build(config.databaseUrl)
  Worker(config, store).run()
}
