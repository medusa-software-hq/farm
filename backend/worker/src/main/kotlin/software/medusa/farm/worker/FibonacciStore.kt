package software.medusa.farm.worker

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.math.BigInteger
import javax.sql.DataSource
import software.medusa.farm.worker.db.FarmWorkerDatabase

/** The worker's write access to the shared domain store (whose schema the API owns). */
class FibonacciStore(private val database: FarmWorkerDatabase) {
  fun highestIndex(): Int? = database.fibonacciQueries.highestIndex().executeAsOneOrNull()

  fun record(index: Int, value: BigInteger) {
    database.fibonacciQueries.record(index, value.toString())
  }

  companion object {
    private const val MAX_POOL_SIZE = 5

    fun build(databaseUrl: String): FibonacciStore {
      val dataSource: DataSource =
          HikariDataSource(
              HikariConfig().apply {
                jdbcUrl = databaseUrl
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = MAX_POOL_SIZE
              })
      return FibonacciStore(FarmWorkerDatabase(dataSource.asJdbcDriver()))
    }
  }
}
