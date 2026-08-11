package software.medusa.farm.worker

import software.medusa.farm.shared.WorkflowServiceAuthConfig

/** Everything the worker needs to reach its database and Temporal. */
data class WorkerConfig(
    val databaseUrl: String,
    val temporalAddress: String,
    val temporalNamespace: String,
    val temporalAuth: WorkflowServiceAuthConfig,
) {
  companion object {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): WorkerConfig =
        WorkerConfig(
            databaseUrl = env["DATABASE_URL"] ?: error("DATABASE_URL is required"),
            temporalAddress = env["TEMPORAL_ADDRESS"] ?: error("TEMPORAL_ADDRESS is required"),
            temporalNamespace =
                env["TEMPORAL_NAMESPACE"] ?: error("TEMPORAL_NAMESPACE is required"),
            temporalAuth =
                WorkflowServiceAuthConfig.Cloud(
                    env["TEMPORAL_API_KEY"] ?: error("TEMPORAL_API_KEY is required")
                ),
        )
  }
}
