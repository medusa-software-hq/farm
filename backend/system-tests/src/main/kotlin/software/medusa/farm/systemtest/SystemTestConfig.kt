package software.medusa.farm.systemtest

import software.medusa.farm.worker.GitHubAppConfig

/**
 * What a system test needs: where the farm's API answers, what it drives, and the App it drives as.
 *
 * Nothing here says how the farm came to be running. One started beside the test and one deployed
 * somewhere are the same thing from here.
 */
data class SystemTestConfig(
    val apiHost: String,
    val apiPort: Int,
    /** The org and repository the loop is driven against. */
    val orgLogin: String,
    val repoName: String,
    /**
     * The App the test works as — filing the issue, asking for changes, merging. Not the farm's:
     * GitHub will not let an App review a pull request it opened, and the harness is not the
     * subject.
     */
    val harnessApp: GitHubAppConfig,
) {
  val repoFullName: String
    get() = "$orgLogin/$repoName"

  companion object {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): SystemTestConfig =
        SystemTestConfig(
            apiHost = env.required("FARM_API_HOST"),
            apiPort = env.required("FARM_API_PORT").toInt(),
            orgLogin = env.required("FARM_TEST_ORG"),
            repoName = env.required("FARM_TEST_REPO"),
            harnessApp =
                GitHubAppConfig(
                    env.required("HARNESS_APP_CLIENT_ID"),
                    env.required("HARNESS_APP_PEM"),
                ),
        )

    // A test missing any of this would either not run or, worse, drive the wrong org.
    private fun Map<String, String>.required(name: String): String =
        this[name] ?: error("$name is required to run the system tests")
  }
}
