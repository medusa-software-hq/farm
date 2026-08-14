package software.medusa.farm.migrate

import software.medusa.farm.shared.FarmStore

private const val databaseUrlEnvVarName = "DATABASE_URL"

/**
 * Applies pending Flyway migrations to the shared database, once, as a deploy step — so neither the
 * API nor the worker migrates on startup. Reads the JDBC URL from [databaseUrlEnvVarName].
 */
fun main() {
  val databaseUrl =
      System.getenv(databaseUrlEnvVarName)
          ?: error("$databaseUrlEnvVarName environment variable must be set")
  val applied = FarmStore.migrate(databaseUrl)
  println("Flyway: applied $applied migration(s).")
}
