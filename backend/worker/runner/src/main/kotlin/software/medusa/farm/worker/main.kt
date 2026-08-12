package software.medusa.farm.worker

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import com.google.cloud.secretmanager.v1.SecretVersionName
import software.medusa.farm.shared.BakedConfig
import software.medusa.farm.shared.WorkflowServiceAuthConfig

private const val runnerEnvironmentEnvVarName = "FARM_RUNNER_ENVIRONMENT"
private const val databaseUrlSecretId = "api-database-url"
private const val temporalApiKeySecretId = "worker-temporal-api-key"
private const val gitHubAppPemSecretId = "api-github-app-pem"

// The client id is a non-secret, per-env identifier (a Terraform var for the API), so the operator
// supplies it via the environment. Required, like the other creds — absent is a setup slip.
private const val gitHubAppClientIdEnvVarName = "GITHUB_APP_CLIENT_ID"

/**
 * Runs the worker locally against a remote database. The database URL comes from the target
 * environment's `api-database-url` secret; the Temporal API key comes from the
 * `worker-temporal-api-key` secret in the cross-environment shared project
 * ([BakedConfig.TEMPORAL_KEY_PROJECT]) — both via Application Default Credentials. The non-secret
 * Temporal coordinates are compile-time constants in [BakedConfig].
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
            // paired with the operator-supplied client id.
            gitHubApp =
                GitHubAppConfig(
                    System.getenv(gitHubAppClientIdEnvVarName)
                        ?: error("$gitHubAppClientIdEnvVarName environment variable must be set"),
                    client.read(environment.gcpProjectId, gitHubAppPemSecretId),
                ),
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
