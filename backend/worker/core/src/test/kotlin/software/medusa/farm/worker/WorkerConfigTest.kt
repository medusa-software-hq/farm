package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import software.medusa.farm.shared.WorkflowServiceAuthConfig

class WorkerConfigTest {
  private val full =
      mapOf(
          "DATABASE_URL" to "jdbc:postgresql://localhost/farm",
          "TEMPORAL_ADDRESS" to "farm.kr9zt.tmprl.cloud:7233",
          "TEMPORAL_NAMESPACE" to "farm.kr9zt",
          "TEMPORAL_API_KEY" to "secret-key",
      )

  @Test
  fun `reads config from the environment`() {
    val config = WorkerConfig.fromEnvironment(full)
    assertEquals("jdbc:postgresql://localhost/farm", config.databaseUrl)
    assertEquals("farm.kr9zt.tmprl.cloud:7233", config.temporalAddress)
    assertEquals("farm.kr9zt", config.temporalNamespace)
    assertEquals(WorkflowServiceAuthConfig.ApiKey("secret-key"), config.temporalAuth)
  }

  @Test
  fun `each variable is required`() {
    for (missing in full.keys) {
      assertFailsWith<IllegalStateException> { WorkerConfig.fromEnvironment(full - missing) }
    }
  }
}
