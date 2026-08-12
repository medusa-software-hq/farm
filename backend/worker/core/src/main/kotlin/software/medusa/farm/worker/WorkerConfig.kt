package software.medusa.farm.worker

import software.medusa.farm.shared.WorkflowServiceAuthConfig

/** Everything the worker needs to reach its database and Temporal, and optionally GitHub. */
data class WorkerConfig(
    val databaseUrl: String,
    val temporalAddress: String,
    val temporalNamespace: String,
    val temporalAuth: WorkflowServiceAuthConfig,
    val gitHubApp: GitHubAppConfig?,
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
            // Optional: without both halves the worker runs but does not host repo sync.
            gitHubApp = gitHubAppFrom(env["GITHUB_APP_CLIENT_ID"], env["GITHUB_APP_PEM"]),
        )

    private fun gitHubAppFrom(clientId: String?, pem: String?): GitHubAppConfig? =
        if (clientId != null && pem != null) GitHubAppConfig(clientId, pem) else null
  }
}
