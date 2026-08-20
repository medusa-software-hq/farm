package software.medusa.farm.ephemeral

import software.medusa.farm.claude.CldEffort
import software.medusa.farm.claude.CldModelId
import software.medusa.farm.github.GhAppPrivateKey
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.worker.GitHubAppConfig
import software.medusa.farm.worker.WorkerConfig

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
    val claudeModel: CldModelId,
    val claudeEffort: CldEffort,
    val openRouterApiKey: String,
    /** Fixed rather than asked for, so whatever drives this farm knows where to find it. */
    val apiPort: Int,
) {
  /**
   * The same configuration the deployed worker takes, so this farm's worker is wired exactly as
   * that one is. Temporal is the only thing that differs: a server beside this process, which needs
   * no credential.
   */
  fun toWorkerConfig(): WorkerConfig =
      WorkerConfig(
          databaseUrl = databaseUrl,
          temporalAddress = TEMPORAL_ADDRESS,
          temporalNamespace = TEMPORAL_NAMESPACE,
          temporalAuth = WorkflowServiceAuthConfig.Local,
          gitHubApp = gitHubApp,
          claudeOauthToken = claudeOauthToken,
          claudeModel = claudeModel,
          claudeEffort = claudeEffort,
          openRouterApiKey = openRouterApiKey,
          commitAuthor = WorkerConfig.commitAuthorFrom(System.getenv()),
          signingKey = WorkerConfig.signingKeyFrom(System.getenv()),
          // This process is the only worker against its own Temporal, so there is nothing here to
          // take work from.
          taskQueue = FarmWorker.DEFAULT_TASK_QUEUE,
      )

  companion object {
    const val TEMPORAL_ADDRESS = "localhost:7233"

    const val TEMPORAL_NAMESPACE = "default"

    fun fromEnvironment(env: Map<String, String> = System.getenv()): EphemeralConfig =
        EphemeralConfig(
            databaseUrl = env.required("DATABASE_URL"),
            gitHubApp =
                GitHubAppConfig(
                    env.required("GITHUB_APP_CLIENT_ID"),
                    GhAppPrivateKey(env.required("GITHUB_APP_PEM")),
                ),
            claudeOauthToken = env.required("CLAUDE_CODE_OAUTH_TOKEN"),
            claudeModel = WorkerConfig.claudeModelFrom(env),
            claudeEffort = WorkerConfig.claudeEffortFrom(env),
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
