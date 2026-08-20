package software.medusa.farm.systemtest

import software.medusa.farm.github.GhAppPrivateKey
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
    /** The org the loop is driven in, and the repository each run is made from. */
    val orgLogin: String,
    val templateRepoName: String,
    /**
     * Names the repository this run makes, and tells a leftover from an earlier run apart from this
     * one's. Given rather than invented so that whatever started the test can find what it left
     * behind.
     */
    val runId: String,
    /** The App the test works as: filing the issue, asking for changes, merging. */
    val fixtureManagerApp: GitHubAppConfig,
) {
  /** The repository this run makes for itself, dropped when it is done with it. */
  val repoName: String
    get() = "$REPO_PREFIX$runId"

  val repoFullName: String
    get() = "$orgLogin/$repoName"

  val templateFullName: String
    get() = "$orgLogin/$templateRepoName"

  companion object {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): SystemTestConfig =
        SystemTestConfig(
            apiHost = env.required("FARM_API_HOST"),
            apiPort = env.required("FARM_API_PORT").toInt(),
            orgLogin = env.required("FARM_TEST_ORG"),
            templateRepoName = env.required("FARM_TEST_TEMPLATE"),
            runId = env.required("FARM_TEST_RUN_ID"),
            fixtureManagerApp =
                GitHubAppConfig(
                    env.required("FIXTURE_MANAGER_APP_CLIENT_ID"),
                    GhAppPrivateKey(env.required("FIXTURE_MANAGER_APP_PEM")),
                ),
        )

    // A test missing any of this would either not run or, worse, drive the wrong org.
    private fun Map<String, String>.required(name: String): String =
        this[name] ?: error("$name is required to run the system tests")

    /** What a repository made by a system test is called, and how a leftover one is recognised. */
    const val REPO_PREFIX = "farm-system-test-"
  }
}
