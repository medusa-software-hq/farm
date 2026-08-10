package software.medusa.farm.worker

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import com.google.cloud.secretmanager.v1.SecretVersionName

private const val runnerEnvironmentEnvVarName = "FARM_RUNNER_ENVIRONMENT"
private const val databaseUrlSecretId = "api-database-url"

/**
 * Local runner entry point: resolves the target environment's `api-database-url` secret from Google
 * Secret Manager via Application Default Credentials, then runs the worker logic against that
 * remote database.
 */
fun main() {
  val environment = resolveRunnerEnvironment(System.getenv(runnerEnvironmentEnvVarName))
  val databaseUrl = readDatabaseUrl(environment)
  runFarmWorker(WorkerConfig.withDefaults(databaseUrl))
}

private fun resolveRunnerEnvironment(raw: String?): RunnerEnvironment {
  if (raw == null) return RunnerEnvironment.PROD
  val allowed = RunnerEnvironment.entries.joinToString(", ") { it.envValue }
  return RunnerEnvironment.entries.firstOrNull { it.envValue == raw }
      ?: error("$runnerEnvironmentEnvVarName must be one of: $allowed (got \"$raw\")")
}

private fun readDatabaseUrl(environment: RunnerEnvironment): String =
    SecretManagerServiceClient.create().use { client ->
      val version = SecretVersionName.of(environment.gcpProjectId, databaseUrlSecretId, "latest")
      client.accessSecretVersion(version).payload.data.toStringUtf8()
    }
