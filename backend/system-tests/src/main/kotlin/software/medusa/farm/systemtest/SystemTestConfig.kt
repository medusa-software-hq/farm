package software.medusa.farm.systemtest

import software.medusa.farm.worker.GitHubAppConfig

/**
 * What a system test needs: the org and repository it drives, the App it drives them as, and — for
 * a farm it has to start itself — what that farm runs on.
 *
 * A test against a deployed farm would want the first half and none of the second.
 */
data class SystemTestConfig(
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
    fun fromEnvironment(env: Map<String, String> = System.getenv()): SystemTestConfig =
        SystemTestConfig(
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
