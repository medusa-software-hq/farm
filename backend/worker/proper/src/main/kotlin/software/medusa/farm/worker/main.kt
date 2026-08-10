package software.medusa.farm.worker

/**
 * Env-configured entry point: reads TEMPORAL_ADDRESS / TEMPORAL_NAMESPACE / TEMPORAL_API_KEY /
 * DATABASE_URL and runs the worker. The worker only hosts; executions are started elsewhere.
 */
fun main() = runTemporalWorker(WorkerConfig.fromEnvironment())
