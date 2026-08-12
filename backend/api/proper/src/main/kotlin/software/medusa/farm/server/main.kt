package software.medusa.farm.server

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

fun main() {
  val port =
      System.getenv(portEnvVarName)?.toIntOrNull()
          ?: error("$portEnvVarName environment variable must be set to a valid integer")

  val webClientId =
      System.getenv(webClientIdEnvVarName)
          ?: error("$webClientIdEnvVarName environment variable must be set")

  // Required: the CLI (Desktop) OAuth client. The API also accepts ID tokens whose audience is the
  // CLI client, so `ms-farm` can call it. This is a distinct OAuth client from the web SPA.
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

  // Optional: without it the API still starts and only StartFibonacci degrades, so a missing or
  // rotating Temporal key can never take the service down.
  val temporalApiKey = System.getenv(temporalApiKeyEnvVarName)

  // Optional, same contract as the Temporal key: absent -> only the GitHub RPCs degrade. Both
  // halves are required together; the PEM must be unencrypted PKCS#8 (see the github-client
  // module).
  val gitHubAppClientId = System.getenv(gitHubAppClientIdEnvVarName)
  val gitHubAppPem = System.getenv(gitHubAppPemEnvVarName)

  val farmStore = FarmStore.buildWithMigrations(databaseUrl)

  // One Temporal auth config drives both starters; absent -> both degrade to no-ops.
  val repoSyncStarter =
      temporalApiKey?.let {
        TemporalRepoSyncStarter(
            BakedConfig.TEMPORAL_ADDRESS,
            BakedConfig.TEMPORAL_NAMESPACE,
            WorkflowServiceAuthConfig.Cloud(it),
        )
      } ?: NoOpRepoSyncStarter

  buildServer(
          originRegex = corsOriginRegex,
          port = port,
          auth =
              GoogleIdTokenAuthDecorator(
                  allowedClientIds = setOf(webClientId, cliClientId),
                  allowedDomain = allowedDomain,
              ),
          farmStore = farmStore,
          fibonacciStarter =
              temporalApiKey?.let {
                TemporalFibonacciStarter(
                    BakedConfig.TEMPORAL_ADDRESS,
                    BakedConfig.TEMPORAL_NAMESPACE,
                    WorkflowServiceAuthConfig.Cloud(it),
                )
              } ?: NoOpFibonacciStarter,
          gitHubOrgs =
              if (gitHubAppClientId != null && gitHubAppPem != null) {
                val appApiClient = GhProperAppApiClient.build(gitHubAppClientId, gitHubAppPem)
                GitHubOrgService(appApiClient, farmStore.linkedOrg, repoSyncStarter)
              } else {
                null
              },
      )
      .start()
      .join()
}
