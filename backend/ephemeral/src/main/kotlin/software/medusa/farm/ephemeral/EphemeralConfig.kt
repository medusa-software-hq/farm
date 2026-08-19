package software.medusa.farm.ephemeral

import software.medusa.farm.worker.GitHubAppConfig

/**
 * What one throwaway run of the whole farm needs. Everything here is real except where the
 * deployment is reached over the network — the database is a branch made for this run, the GitHub
 * App is one installed on a test org, and Temporal is a server started beside it.
 */
data class EphemeralConfig(
    /** A Neon branch, made and dropped around this run rather than by anything here. */
    val databaseUrl: String,
    val gitHubApp: GitHubAppConfig,
    /** The org the App is installed on, and the repository the loop is driven against. */
    val orgLogin: String,
    val repoName: String,
    val claudeOauthToken: String,
    val openRouterApiKey: String,
    /** Where the API listens. Zero asks the OS for a free one, which is what lets runs overlap. */
    val apiPort: Int,
) {
  val repoFullName: String
    get() = "$orgLogin/$repoName"

  companion object {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): EphemeralConfig =
        EphemeralConfig(
            databaseUrl = env.required("DATABASE_URL"),
            gitHubApp =
                GitHubAppConfig(
                    env.required("GITHUB_APP_CLIENT_ID"),
                    env.required("GITHUB_APP_PEM"),
                ),
            orgLogin = env.required("FARM_EPHEMERAL_ORG"),
            repoName = env.required("FARM_EPHEMERAL_REPO"),
            claudeOauthToken = env.required("CLAUDE_CODE_OAUTH_TOKEN"),
            openRouterApiKey = env.required("OPENROUTER_API_KEY"),
            apiPort = 0,
        )

    // Nothing here has a sensible stand-in: a run missing any of it would either not start or,
    // worse, drive the wrong org.
    private fun Map<String, String>.required(name: String): String =
        this[name] ?: error("$name is required to run the ephemeral farm")
  }
}
