package software.medusa.farm.ephemeral

import software.medusa.farm.worker.GitHubAppConfig

/**
 * What one throwaway farm runs on. Everything here is real except Temporal, which runs beside this
 * process rather than in the cloud — the database is made for this run and the GitHub App is one
 * installed on a test org.
 */
data class EphemeralConfig(
    /** Made and dropped around this process rather than by it. */
    val databaseUrl: String,
    /** The App the farm works as: it opens the pull request and pushes the fixups. */
    val gitHubApp: GitHubAppConfig,
    val claudeOauthToken: String,
    val openRouterApiKey: String,
    /** Fixed rather than asked for, so whatever drives this farm knows where to find it. */
    val apiPort: Int,
) {
  companion object {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): EphemeralConfig =
        EphemeralConfig(
            databaseUrl = env.required("DATABASE_URL"),
            gitHubApp =
                GitHubAppConfig(
                    env.required("GITHUB_APP_CLIENT_ID"),
                    env.required("GITHUB_APP_PEM"),
                ),
            claudeOauthToken = env.required("CLAUDE_CODE_OAUTH_TOKEN"),
            openRouterApiKey = env.required("OPENROUTER_API_KEY"),
            apiPort = env["FARM_API_PORT"]?.toInt() ?: DEFAULT_API_PORT,
        )

    // Nothing here has a sensible stand-in: a farm missing any of it would either not start or,
    // worse, work against the wrong org.
    private fun Map<String, String>.required(name: String): String =
        this[name] ?: error("$name is required to run an ephemeral farm")

    private const val DEFAULT_API_PORT = 8081
  }
}
