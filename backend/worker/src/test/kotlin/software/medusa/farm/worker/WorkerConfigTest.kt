package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class WorkerConfigTest {
  @Test
  fun `reads config from the environment`() {
    val config =
        WorkerConfig.fromEnvironment(
            mapOf(
                "DATABASE_URL" to "jdbc:postgresql://localhost/farm",
                "FIB_TICK_MS" to "250",
                "FIB_MAX_INDEX" to "10",
            )
        )
    assertEquals("jdbc:postgresql://localhost/farm", config.databaseUrl)
    assertEquals(250.milliseconds, config.tick)
    assertEquals(10, config.maxIndex)
  }

  @Test
  fun `tick and max index default when unset`() {
    val config = WorkerConfig.fromEnvironment(mapOf("DATABASE_URL" to "x"))
    assertEquals(500.milliseconds, config.tick)
    assertEquals(40, config.maxIndex)
  }

  @Test
  fun `DATABASE_URL is required`() {
    assertFailsWith<IllegalStateException> { WorkerConfig.fromEnvironment(emptyMap()) }
  }
}
