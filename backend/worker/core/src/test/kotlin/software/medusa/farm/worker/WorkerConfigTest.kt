package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import software.medusa.farm.gitcli.GitCliAuthor
import software.medusa.farm.shared.WorkflowServiceAuthConfig

class WorkerConfigTest {
  private val full =
      mapOf(
          "DATABASE_URL" to "jdbc:postgresql://localhost/farm",
          "TEMPORAL_ADDRESS" to "farm.kr9zt.tmprl.cloud:7233",
          "TEMPORAL_NAMESPACE" to "farm.kr9zt",
          "TEMPORAL_API_KEY" to "secret-key",
          "GITHUB_APP_CLIENT_ID" to "Iv1.test",
          "GITHUB_APP_PEM" to pemStandIn,
          "CLAUDE_CODE_OAUTH_TOKEN" to "sk-ant-oat01-test",
          "OPENROUTER_API_KEY" to "sk-or-test",
      )

  @Test
  fun `reads config from the environment`() {
    val config = WorkerConfig.fromEnvironment(full)
    assertEquals("jdbc:postgresql://localhost/farm", config.databaseUrl)
    assertEquals("farm.kr9zt.tmprl.cloud:7233", config.temporalAddress)
    assertEquals("farm.kr9zt", config.temporalNamespace)
    assertEquals(WorkflowServiceAuthConfig.Cloud("secret-key"), config.temporalAuth)
    assertEquals("Iv1.test", config.gitHubApp.clientId)
    assertEquals(pemStandIn, config.gitHubApp.privateKey.pem)
    assertEquals("sk-ant-oat01-test", config.claudeOauthToken)
    // Commit identity defaults to Farm's, and signing is off unless a key is provided.
    assertEquals(GitCliAuthor("Farm", "farm@medusa.software"), config.commitAuthor)
    assertNull(config.signingKey)
  }

  @Test
  fun `commit identity and signing key are overridable`() {
    val config =
        WorkerConfig.fromEnvironment(
            full +
                mapOf(
                    "FARM_COMMIT_AUTHOR_NAME" to "Acme Bot",
                    "FARM_COMMIT_AUTHOR_EMAIL" to "bot@acme.example",
                    "FARM_COMMIT_SIGNING_KEY" to "ABCD1234",
                )
        )
    assertEquals(GitCliAuthor("Acme Bot", "bot@acme.example"), config.commitAuthor)
    assertEquals("ABCD1234", config.signingKey)
  }

  @Test
  fun `each variable is required`() {
    for (missing in full.keys) {
      assertFailsWith<IllegalStateException> { WorkerConfig.fromEnvironment(full - missing) }
    }
  }

  private companion object {
    // Shaped like a key rather than being one: the config only carries it, and GhAppPrivateKey
    // refuses anything that is not at least shaped like one.
    const val pemStandIn = "-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----"
  }
}
