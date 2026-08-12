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

  // Required (every env configures Temporal): a missing key is a misconfiguration, so fail fast.
  val temporalApiKey =
      System.getenv(temporalApiKeyEnvVarName)
          ?: error("$temporalApiKeyEnvVarName environment variable must be set")
  val temporalAuth = WorkflowServiceAuthConfig.Cloud(temporalApiKey)

  // Required (every env configures the GitHub App): a missing or half-set pair is a
  // misconfiguration, so fail fast rather than silently disable GitHub. The PEM must be unencrypted
  // PKCS#8 (see the github-client module).
  val gitHubAppClientId =
      System.getenv(gitHubAppClientIdEnvVarName)
          ?: error("$gitHubAppClientIdEnvVarName environment variable must be set")
  val gitHubAppPem =
      System.getenv(gitHubAppPemEnvVarName)
          ?: error("$gitHubAppPemEnvVarName environment variable must be set")

  val farmStore = FarmStore.buildWithMigrations(databaseUrl)

  // One Temporal auth config drives both starters.
  val repoSyncStarter =
      TemporalRepoSyncStarter(
          BakedConfig.TEMPORAL_ADDRESS,
          BakedConfig.TEMPORAL_NAMESPACE,
          temporalAuth,
      )

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
      )
      .start()
      .join()
}
