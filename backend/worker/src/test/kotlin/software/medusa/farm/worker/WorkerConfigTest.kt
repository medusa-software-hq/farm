package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerConfigTest {
  private val required =
      mapOf(
          "DATABASE_URL" to "jdbc:postgresql://localhost/farm",
          "FARM_GITHUB_APP_ID" to "123",
          "FARM_GITHUB_APP_PEM" to "-----BEGIN PRIVATE KEY-----",
          "FARM_ORG_OWNER" to "medusa-software-hq",
      )

  @Test
  fun `defaults temporal address and namespace`() {
    val config = WorkerConfig.fromEnvironment(required)

    assertEquals("127.0.0.1:7233", config.temporal.address)
    assertEquals("farm", config.temporal.namespace)
  }

  @Test
  fun `task queues are shared consts, not env-configured`() {
    assertEquals("farm-pipeline", TaskQueues.pipeline)
    assertEquals("farm-engine", TaskQueues.engine)
  }

  @Test
  fun `reads temporal overrides from the environment`() {
    val config =
        WorkerConfig.fromEnvironment(
            required +
                mapOf(
                    "TEMPORAL_ADDRESS" to "temporal:7233",
                    "TEMPORAL_NAMESPACE" to "farm-staging",
                )
        )

    assertEquals("temporal:7233", config.temporal.address)
    assertEquals("farm-staging", config.temporal.namespace)
  }

  @Test
  fun `fails fast when a required variable is missing`() {
    assertFailsWith<IllegalStateException> { WorkerConfig.fromEnvironment(emptyMap()) }
  }
}
