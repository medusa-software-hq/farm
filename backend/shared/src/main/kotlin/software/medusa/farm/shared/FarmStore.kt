package software.medusa.farm.shared

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import software.medusa.farm.shared.db.FarmDatabase

private const val maxPoolSize = 5

/** The farm's stores over the one shared database. */
class FarmStore(
    val linkedOrg: LinkedOrgStore,
    val repo: RepoStore,
    val issue: IssueStore,
    val session: SessionStore,
) {
  companion object {
    /**
     * Applies pending Flyway migrations to the database and returns the number applied, then
     * releases the pool. Migrations are a dedicated deploy step (run once for the shared database),
     * so neither the API nor the worker migrates on startup — they just [build].
     */
    fun migrate(jdbcUrl: String): Int =
        buildDataSource(jdbcUrl).use { dataSource ->
          Flyway.configure().apply { dataSource(dataSource) }.load().migrate().migrationsExecuted
        }

    /** Builds the [FarmStore]. Schema migrations are applied separately — see [migrate]. */
    fun build(jdbcUrl: String): FarmStore = storesOver(buildDataSource(jdbcUrl))

    private fun storesOver(dataSource: DataSource): FarmStore {
      val database = FarmDatabase(dataSource.asJdbcDriver())
      return FarmStore(
          PostgresLinkedOrgStore(database),
          PostgresRepoStore(database),
          PostgresIssueStore(database),
          PostgresSessionStore(database),
      )
    }

    private fun buildDataSource(jdbcUrl: String): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
              this.jdbcUrl = jdbcUrl
              // Register the driver explicitly instead of relying on DriverManager's ServiceLoader
              // auto-registration, which is unreliable in the packaged Cloud Run image (it fails
              // with "No suitable driver" even though pgjdbc is on the classpath).
              driverClassName = "org.postgresql.Driver"
              maximumPoolSize = maxPoolSize
            }
        )
  }
}
