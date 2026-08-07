package software.medusa.farm.worker.activity.impl

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import software.medusa.farm.worker.db.FarmWorkerDatabase

private const val maxPoolSize = 5

/**
 * The domain store + read model that the web console and `ms-farm` query. Temporal owns in-flight
 * orchestration state, timers and retries; this DB owns durable domain records (repos, pipelines,
 * sessions, results). Follows Farm's `PostgresCounterStore` wiring: Flyway owns the runtime schema,
 * SQLDelight's `CREATE TABLE` mirrors it for compile-time query verification only.
 */
class WorkerDomainStore(private val database: FarmWorkerDatabase) {
  companion object {
    fun build(jdbcUrl: String): WorkerDomainStore {
      val dataSource: DataSource =
          HikariDataSource(
              HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = maxPoolSize
              }
          )

      Flyway.configure().apply { dataSource(dataSource) }.load().migrate()

      return WorkerDomainStore(FarmWorkerDatabase(dataSource.asJdbcDriver()))
    }
  }

  // TODO: expose typed upsert/record methods backed by database.pipelineQueries.* /
  //  database.sessionQueries.*, wrapped in withContext(Dispatchers.IO) like PostgresCounterStore.
}
