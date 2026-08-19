package software.medusa.farm.worker

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import com.google.cloud.secretmanager.v1.SecretVersionName
import software.medusa.farm.shared.BakedConfig
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.WorkflowServiceAuthConfig

private const val runnerEnvironmentEnvVarName = "FARM_RUNNER_ENVIRONMENT"
private const val claudeOauthTokenEnvVarName = "CLAUDE_CODE_OAUTH_TOKEN"
private const val databaseUrlSecretId = "api-database-url"
private const val temporalApiKeySecretId = "worker-temporal-api-key"
private const val gitHubAppPemSecretId = "api-github-app-pem"
private const val openRouterApiKeySecretId = "worker-openrouter-api-key"

/**
 * Runs the worker locally against a remote database. The database URL comes from the target
 * environment's `api-database-url` secret; the Temporal API key comes from the
 * `worker-temporal-api-key` secret in the cross-environment shared project
 * ([BakedConfig.TEMPORAL_KEY_PROJECT]) — both via Application Default Credentials. The non-secret
 * Temporal coordinates and the per-environment GitHub App client id are baked in, so the only env
 * var is the environment selector.
 */
fun main() {
  val environment = resolveRunnerEnvironment(System.getenv(runnerEnvironmentEnvVarName))
  val config =
      SecretManagerServiceClient.create().use { client ->
        WorkerConfig(
            databaseUrl = client.read(environment.gcpProjectId, databaseUrlSecretId),
            temporalAddress = BakedConfig.TEMPORAL_ADDRESS,
            temporalNamespace = BakedConfig.TEMPORAL_NAMESPACE,
            temporalAuth =
                WorkflowServiceAuthConfig.Cloud(
                    client.read(BakedConfig.TEMPORAL_KEY_PROJECT, temporalApiKeySecretId)
                ),
            // The PEM comes from this env's `api-github-app-pem` secret (same path as the DB URL),
            // paired with the baked per-environment client id.
            gitHubApp =
                GitHubAppConfig(
                    environment.gitHubAppClientId,
                    client.read(environment.gcpProjectId, gitHubAppPemSecretId),
                ),
            // The operator's own claude token, from the launch environment — the worker runs the
            // `claude` binary on the operator's machine, so its auth comes from there too.
            claudeOauthToken =
                System.getenv(claudeOauthTokenEnvVarName)
                    ?: error("$claudeOauthTokenEnvVarName is required (from `claude setup-token`)"),
            // Keys the summary model. A service credential reached over HTTP, so it comes from
            // the environment's secrets rather than the launch environment.
            openRouterApiKey = client.read(environment.gcpProjectId, openRouterApiKeySecretId),
            commitAuthor = WorkerConfig.commitAuthorFrom(System.getenv()),
            signingKey = WorkerConfig.signingKeyFrom(System.getenv()),
            taskQueue = FarmWorker.taskQueueFrom(),
        )
      }
  runTemporalWorker(config)
}

private fun resolveRunnerEnvironment(raw: String?): RunnerEnvironment {
  if (raw == null) return RunnerEnvironment.PROD
  val allowed = RunnerEnvironment.entries.joinToString(", ") { it.envValue }
  return RunnerEnvironment.entries.firstOrNull { it.envValue == raw }
      ?: error("$runnerEnvironmentEnvVarName must be one of: $allowed (got \"$raw\")")
}

private fun SecretManagerServiceClient.read(projectId: String, secretId: String): String {
  val version = SecretVersionName.of(projectId, secretId, "latest")
  return accessSecretVersion(version).payload.data.toStringUtf8()
}
