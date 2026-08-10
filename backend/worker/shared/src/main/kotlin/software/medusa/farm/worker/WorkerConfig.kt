package software.medusa.farm.worker

/** Everything the worker needs to reach its database and its Temporal Cloud namespace. */
data class WorkerConfig(
    val databaseUrl: String,
    val temporalAddress: String,
    val temporalNamespace: String,
    val temporalApiKey: String,
) {
  companion object {
    /** Reads the config from process environment variables. */
    fun fromEnvironment(env: Map<String, String> = System.getenv()): WorkerConfig =
        WorkerConfig(
            databaseUrl = env["DATABASE_URL"] ?: error("DATABASE_URL is required"),
            temporalAddress = env["TEMPORAL_ADDRESS"] ?: error("TEMPORAL_ADDRESS is required"),
            temporalNamespace =
                env["TEMPORAL_NAMESPACE"] ?: error("TEMPORAL_NAMESPACE is required"),
            temporalApiKey = env["TEMPORAL_API_KEY"] ?: error("TEMPORAL_API_KEY is required"),
        )
  }
}
