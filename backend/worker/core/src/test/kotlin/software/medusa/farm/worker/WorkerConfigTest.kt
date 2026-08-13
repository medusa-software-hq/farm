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
          "GITHUB_APP_CLIENT_ID" to "Iv1.test",
          "GITHUB_APP_PEM" to "-----BEGIN PRIVATE KEY-----",
          "CLAUDE_CODE_OAUTH_TOKEN" to "sk-ant-oat01-test",
      )

  @Test
  fun `reads config from the environment`() {
    val config = WorkerConfig.fromEnvironment(full)
    assertEquals("jdbc:postgresql://localhost/farm", config.databaseUrl)
    assertEquals("farm.kr9zt.tmprl.cloud:7233", config.temporalAddress)
    assertEquals("farm.kr9zt", config.temporalNamespace)
    assertEquals(WorkflowServiceAuthConfig.Cloud("secret-key"), config.temporalAuth)
    assertEquals("Iv1.test", config.gitHubApp.clientId)
    assertEquals("-----BEGIN PRIVATE KEY-----", config.gitHubApp.pem)
    assertEquals("sk-ant-oat01-test", config.claudeOauthToken)
  }

  @Test
  fun `each variable is required`() {
    for (missing in full.keys) {
      assertFailsWith<IllegalStateException> { WorkerConfig.fromEnvironment(full - missing) }
    }
  }
}
