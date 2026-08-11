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
    val fibonacci: FibonacciStore,
    val linkedOrg: LinkedOrgStore,
) {
  companion object {
    /** Builds the [FarmStore], applying runtime database schema migrations. */
    fun buildWithMigrations(jdbcUrl: String): FarmStore {
      val dataSource = buildDataSource(jdbcUrl)
      Flyway.configure().apply { dataSource(dataSource) }.load().migrate()
      return storesOver(dataSource)
    }

    /** Builds the [FarmStore] **without** applying runtime database schema migrations. */
    fun buildWithoutMigrations(jdbcUrl: String): FarmStore = storesOver(buildDataSource(jdbcUrl))

    private fun storesOver(dataSource: DataSource): FarmStore {
      val database = FarmDatabase(dataSource.asJdbcDriver())
      return FarmStore(
          PostgresFibonacciStore(database),
          PostgresLinkedOrgStore(database),
      )
    }

    private fun buildDataSource(jdbcUrl: String): DataSource =
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
