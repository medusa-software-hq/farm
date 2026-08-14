package software.medusa.farm.worker

import software.medusa.farm.gitcli.GitCliAuthor
import software.medusa.farm.shared.WorkflowServiceAuthConfig

/** Everything the worker needs to reach its database, Temporal, and GitHub. */
data class WorkerConfig(
    val databaseUrl: String,
    val temporalAddress: String,
    val temporalNamespace: String,
    val temporalAuth: WorkflowServiceAuthConfig,
    val gitHubApp: GitHubAppConfig,
    // The claude CLI auth token the agent runs under (from `claude setup-token`).
    val claudeOauthToken: String,
    // The identity Farm's commits carry, and the optional GPG key to sign them with.
    val commitAuthor: GitCliAuthor,
    val signingKey: String?,
) {
  companion object {
    private const val defaultCommitAuthorName = "Farm"
    private const val defaultCommitAuthorEmail = "farm@medusa.software"

    /** Farm's commit identity, overridable per deployment; the signing key is optional. */
    fun commitAuthorFrom(env: Map<String, String>): GitCliAuthor =
        GitCliAuthor(
            name = env["FARM_COMMIT_AUTHOR_NAME"] ?: defaultCommitAuthorName,
            email = env["FARM_COMMIT_AUTHOR_EMAIL"] ?: defaultCommitAuthorEmail,
        )

    fun signingKeyFrom(env: Map<String, String>): String? = env["FARM_COMMIT_SIGNING_KEY"]

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
            // Required to run the repo-sync fetch: a missing half is a misconfiguration, so fail
            // fast rather than run a worker that cannot sync.
            gitHubApp =
                GitHubAppConfig(
                    env["GITHUB_APP_CLIENT_ID"] ?: error("GITHUB_APP_CLIENT_ID is required"),
                    env["GITHUB_APP_PEM"] ?: error("GITHUB_APP_PEM is required"),
                ),
            claudeOauthToken =
                env["CLAUDE_CODE_OAUTH_TOKEN"] ?: error("CLAUDE_CODE_OAUTH_TOKEN is required"),
            commitAuthor = commitAuthorFrom(env),
            signingKey = signingKeyFrom(env),
        )
  }
}
