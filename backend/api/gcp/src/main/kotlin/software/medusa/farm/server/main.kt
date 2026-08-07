package software.medusa.farm.server

private const val portEnvVarName = "PORT"
private const val webClientIdEnvVarName = "GOOGLE_WEB_CLIENT_ID"
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

  buildServer(
          originRegex = corsOriginRegex,
          port = port,
          auth =
              GoogleIdTokenAuthDecorator(
                  allowedClientIds = setOf(webClientId, cliClientId),
                  allowedDomain = allowedDomain,
              ),
          counterStore = PostgresCounterStore.build(databaseUrl),
      )
      .start()
      .join()
}
