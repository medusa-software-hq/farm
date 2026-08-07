package software.medusa.farm.worker

/**
 * Arg-less, env-configured entrypoint for the Farm worker.
 *
 * This is NOT a CLI (contrast with Flow's `flow work` subcommand and with `ms-farm`, Farm's
 * admin-only CLI). It is a container-style long-running process: it reads all configuration from
 * the environment, connects to Temporal, registers the pipeline workflows + activities on the task
 * queue, and runs until the process is signalled to stop.
 */
fun main() {
  val config = WorkerConfig.fromEnvironment()
  TemporalWorkerHost(config).run()
}
