package software.medusa.farm.worker

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import com.google.cloud.secretmanager.v1.SecretVersionName

private const val runnerEnvironmentEnvVarName = "FARM_RUNNER_ENVIRONMENT"
private const val databaseUrlSecretId = "api-database-url"
private const val temporalApiKeySecretId = "worker-temporal-api-key"

// Non-secret Temporal Cloud connection values — one namespace shared across environments for now
// (see infra/temporal). The worker key is a secret and comes from Secret Manager, below.
private const val temporalAddress = "farm.kr9zt.tmprl.cloud:7233"
private const val temporalNamespace = "farm.kr9zt"

/**
 * Runs the worker locally against a remote database, configured from Google Secret Manager via
 * Application Default Credentials instead of the environment: the target environment's
 * `api-database-url` and the (shared) prod `worker-temporal-api-key`.
 */
fun main() {
  val environment = resolveRunnerEnvironment(System.getenv(runnerEnvironmentEnvVarName))
  val config =
      SecretManagerServiceClient.create().use { client ->
        WorkerConfig(
            databaseUrl = client.read(environment.gcpProjectId, databaseUrlSecretId),
            temporalAddress = temporalAddress,
            temporalNamespace = temporalNamespace,
            temporalApiKey =
                client.read(RunnerEnvironment.PROD.gcpProjectId, temporalApiKeySecretId),
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
