package software.medusa.farm.server

import software.medusa.farm.shared.FarmStore

private const val portEnvVarName = "PORT"
private const val webClientIdEnvVarName = "GOOGLE_WEB_CLIENT_ID"

// Transitional fallback for the web client id. The env var was renamed
// GOOGLE_CLIENT_ID -> GOOGLE_WEB_CLIENT_ID, but that env is Terraform-managed while
// the image is deployed by a separate workflow, so the rename is not atomic: a
// revision can run the new image before the env has been renamed (this is what
// broke prod). Accepting the old name too makes the boot order-independent.
// Remove once every environment's Cloud Run env is on GOOGLE_WEB_CLIENT_ID.
private const val legacyWebClientIdEnvVarName = "GOOGLE_CLIENT_ID"
private const val cliClientIdEnvVarName = "GOOGLE_CLI_CLIENT_ID"
private const val allowedDomainEnvVarName = "GOOGLE_ALLOWED_DOMAIN"
private const val corsOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"
private const val databaseUrlEnvVarName = "DATABASE_URL"

fun main() {
  val port =
      System.getenv(portEnvVarName)?.toIntOrNull()
          ?: error("$portEnvVarName environment variable must be set to a valid integer")

  val webClientId =
      System.getenv(webClientIdEnvVarName)
          ?: System.getenv(legacyWebClientIdEnvVarName)
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

  val farmStore = FarmStore.buildWithMigrations(databaseUrl)

  buildServer(
          originRegex = corsOriginRegex,
          port = port,
          auth =
              GoogleIdTokenAuthDecorator(
                  allowedClientIds = setOf(webClientId, cliClientId),
                  allowedDomain = allowedDomain,
              ),
          farmStore = farmStore,
      )
      .start()
      .join()
}
