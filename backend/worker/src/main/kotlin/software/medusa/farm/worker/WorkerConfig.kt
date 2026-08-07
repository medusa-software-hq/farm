package software.medusa.farm.worker

/**
 * All worker configuration, read from the environment. The worker is deliberately NOT a CLI: it
 * takes no arguments and is configured entirely through env vars, like a container image. See
 * [fromEnvironment].
 */
data class WorkerConfig(
    val temporal: TemporalConfig,
    val database: DatabaseConfig,
    val github: GitHubConfig,
    val engine: EngineConfig,
    val orgOwner: String,
) {
  companion object {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): WorkerConfig =
        WorkerConfig(
            temporal =
                TemporalConfig(
                    address = env["TEMPORAL_ADDRESS"] ?: "127.0.0.1:7233",
                    namespace = env["TEMPORAL_NAMESPACE"] ?: "farm",
                    taskQueue = env["FARM_WORKER_TASK_QUEUE"] ?: TaskQueues.pipeline,
                ),
            database =
                DatabaseConfig(
                    jdbcUrl = env["DATABASE_URL"] ?: error("DATABASE_URL is required"),
                ),
            github =
                GitHubConfig(
                    appId = env["FARM_GITHUB_APP_ID"] ?: error("FARM_GITHUB_APP_ID is required"),
                    appPrivateKeyPem =
                        env["FARM_GITHUB_APP_PEM"] ?: error("FARM_GITHUB_APP_PEM is required"),
                ),
            engine =
                EngineConfig(
                    defaultEngine = env["FARM_ENGINE"] ?: "claude",
                    engineApiKey = env["FARM_ENGINE_API_KEY"],
                ),
            orgOwner = env["FARM_ORG_OWNER"] ?: error("FARM_ORG_OWNER is required"),
        )
  }
}

/**
 * Temporal connection + placement. Mirrors the org demo's `TEMPORAL_ADDRESS`/`TEMPORAL_NAMESPACE`.
 */
data class TemporalConfig(val address: String, val namespace: String, val taskQueue: String)

/** Postgres domain store + read model connection (full JDBC URL incl. credentials and sslmode). */
data class DatabaseConfig(val jdbcUrl: String)

/** GitHub App installation credentials used to mint per-repo installation tokens. */
data class GitHubConfig(val appId: String, val appPrivateKeyPem: String)

/** Engine selection + credentials for the long agent run. */
data class EngineConfig(val defaultEngine: String, val engineApiKey: String?)
