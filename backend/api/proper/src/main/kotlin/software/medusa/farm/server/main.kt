package software.medusa.farm.server

import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.shared.BakedConfig
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.WorkflowServiceAuthConfig

private const val portEnvVarName = "PORT"
private const val webClientIdEnvVarName = "GOOGLE_WEB_CLIENT_ID"
private const val cliClientIdEnvVarName = "GOOGLE_CLI_CLIENT_ID"
private const val allowedDomainEnvVarName = "GOOGLE_ALLOWED_DOMAIN"
private const val corsOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"
private const val databaseUrlEnvVarName = "DATABASE_URL"
private const val temporalApiKeyEnvVarName = "TEMPORAL_API_KEY"
private const val gitHubAppClientIdEnvVarName = "GITHUB_APP_CLIENT_ID"
private const val gitHubAppPemEnvVarName = "GITHUB_APP_PEM"

private val logger = LoggerFactory.getLogger("software.medusa.farm.server.MainKt")

suspend fun main() {
  coroutineScope {
    val port =
        System.getenv(portEnvVarName)?.toIntOrNull()
            ?: error("$portEnvVarName environment variable must be set to a valid integer")

    val webClientId =
        System.getenv(webClientIdEnvVarName)
            ?: error("$webClientIdEnvVarName environment variable must be set")

    // Required: the CLI (Desktop) OAuth client. The API also accepts ID tokens whose audience
    // is the CLI client, so `ms-farm` can call it — a distinct OAuth client from the web SPA.
    val cliClientId =
        System.getenv(cliClientIdEnvVarName)
            ?: error("$cliClientIdEnvVarName environment variable must be set")

    val allowedDomain =
        System.getenv(allowedDomainEnvVarName)
            ?: error("$allowedDomainEnvVarName environment variable must be set")

    val corsOriginRegex =
        System.getenv(corsOriginRegexEnvVarName)
            ?: error("$corsOriginRegexEnvVarName environment variable must be set")

    val databaseUrl =
        System.getenv(databaseUrlEnvVarName)
            ?: error("$databaseUrlEnvVarName environment variable must be set")

    // Required (every env configures Temporal): a missing key is a misconfiguration, so fail fast.
    val temporalApiKey =
        System.getenv(temporalApiKeyEnvVarName)
            ?: error("$temporalApiKeyEnvVarName environment variable must be set")
    val temporalAuth = WorkflowServiceAuthConfig.Cloud(temporalApiKey)

    // Required (every env configures the GitHub App): a missing or half-set pair is a
    // misconfiguration, so fail fast rather than silently disable GitHub. The PEM must be
    // unencrypted PKCS#8 (see the github-client module).
    val gitHubAppClientId =
        System.getenv(gitHubAppClientIdEnvVarName)
            ?: error("$gitHubAppClientIdEnvVarName environment variable must be set")
    val gitHubAppPem =
        System.getenv(gitHubAppPemEnvVarName)
            ?: error("$gitHubAppPemEnvVarName environment variable must be set")

    val farmStore = FarmStore.buildWithMigrations(databaseUrl)

    // Build the shared Temporal client off the critical path so the server binds without waiting
    // on its gRPC/Netty/TLS init (~6s of the cold start). The list RPCs don't need Temporal;
    // link/sync await this Deferred and suspend only if a request races the build. Both starters
    // share the one client — one gRPC channel, not two.
    val temporalClient =
        async(Dispatchers.IO) {
          buildWorkflowClient(
              BakedConfig.TEMPORAL_ADDRESS,
              BakedConfig.TEMPORAL_NAMESPACE,
              temporalAuth,
          )
        }
    // A build failure is a config bug, not Temporal being unreachable (the health check is
    // disabled, so the build does no network). Crash rather than serve with permanently-broken
    // sync; Cloud Run replaces the instance, and a deterministic failure fails loudly instead of
    // silently degrading.
    temporalClient.invokeOnCompletion { cause ->
      if (cause != null) {
        logger.error("Temporal client init failed; exiting", cause)
        exitProcess(1)
      }
    }
    val repoSyncStarter = TemporalRepoSyncStarter(temporalClient)
    val syncAllStarter = TemporalSyncAllStarter(temporalClient)

    buildServer(
            originRegex = corsOriginRegex,
            port = port,
            auth =
                GoogleIdTokenAuthDecorator(
                    allowedClientIds = setOf(webClientId, cliClientId),
                    allowedDomain = allowedDomain,
                ),
            farmStore = farmStore,
            gitHubOrgs =
                GitHubOrgService(
                    GhProperAppApiClient.build(gitHubAppClientId, gitHubAppPem),
                    farmStore.linkedOrg,
                    repoSyncStarter,
                ),
            syncAllStarter = syncAllStarter,
        )
        .start()
        .join()
  }
}
