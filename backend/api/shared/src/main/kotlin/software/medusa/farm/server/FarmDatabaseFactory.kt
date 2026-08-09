package software.medusa.farm.server

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import software.medusa.farm.db.FarmDatabase

private const val maxPoolSize = 5

/**
 * Builds the process-wide connection to the farm database at [jdbcUrl] (a full JDBC URL including
 * credentials and `sslmode=require`), running pending Flyway migrations before returning.
 *
 * The API owns the schema: this is the one place migrations run. Every store shares the returned
 * [FarmDatabase] so the process keeps a single pool.
 */
fun buildFarmDatabase(jdbcUrl: String): FarmDatabase {
  val dataSource: DataSource =
      HikariDataSource(
          HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            // Register the driver explicitly instead of relying on DriverManager's ServiceLoader
            // auto-registration, which is unreliable in the packaged Cloud Run image (it fails with
            // "No suitable driver" even though pgjdbc is on the classpath).
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = maxPoolSize
          })
  Flyway.configure().apply { dataSource(dataSource) }.load().migrate()
  return FarmDatabase(dataSource.asJdbcDriver())
}
