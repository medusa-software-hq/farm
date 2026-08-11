package software.medusa.farm.cli.config

import com.nimbusds.oauth2.sdk.id.ClientID
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import software.medusa.farm.cli.api.ApiEndpoint

class EnvironmentTest {
  @Test
  fun `absent or prod selects Prod`() {
    assertEquals(Environment.Prod, Environment.current(null, null, null))
    assertEquals(Environment.Prod, Environment.current("prod", null, null))
  }

  @Test
  fun `staging selects Staging`() {
    assertEquals(Environment.Staging, Environment.current("staging", null, null))
  }

  @Test
  fun `matching is exact — blanks, case, and aliases are rejected`() {
    assertFailsWith<EnvironmentSelectionException> { Environment.current("", null, null) }
    assertFailsWith<EnvironmentSelectionException> { Environment.current("PROD", null, null) }
    assertFailsWith<EnvironmentSelectionException> { Environment.current("production", null, null) }
    assertFailsWith<EnvironmentSelectionException> { Environment.current(" staging ", null, null) }
    assertFailsWith<EnvironmentSelectionException> { Environment.current("prd", null, null) }
  }

  @Test
  fun `local requires config path and a valid port`() {
    val env = Environment.current("local", "/tmp/x", "8081")
    assertTrue(env is Environment.Local)
    assertEquals(ApiEndpoint("127.0.0.1", 8081, useTls = false), env.apiEndpoint)
    assertFailsWith<EnvironmentSelectionException> { Environment.current("local", null, "8081") }
    assertFailsWith<EnvironmentSelectionException> { Environment.current("local", "/tmp/x", null) }
    assertFailsWith<EnvironmentSelectionException> {
      Environment.current("local", "/tmp/x", "nope")
    }
  }

  @Test
  fun `prod and staging are fully partitioned`() {
    val base = Path.of("/base")
    assertEquals(Path.of("/base/prod"), Environment.Prod.resolveConfigDirPath(base))
    assertEquals(Path.of("/base/staging"), Environment.Staging.resolveConfigDirPath(base))
    assertNotEquals(
        Environment.Prod.resolveConfigDirPath(base),
        Environment.Staging.resolveConfigDirPath(base),
    )
    // Separate OAuth clients per environment — the credential boundary is the environment boundary.
    assertNotEquals(Environment.Prod.oauthClientId, Environment.Staging.oauthClientId)
    assertEquals(null, Environment.Prod.marker)
    assertEquals("[staging]", Environment.Staging.marker)
  }

  @Test
  fun `prod and staging use the Farm environments, not another project's`() {
    // The generated config comes from infra/environments' cache. These are Farm's
    // Desktop OAuth clients and hosts; regression guard against the earlier drift
    // where the CLI hardcoded a different project's client ids.
    assertEquals(
        ClientID("97246827152-d2e73hif1ckri70a6osh139v6jmaesag.apps.googleusercontent.com"),
        Environment.Prod.oauthClientId,
    )
    assertEquals("api.farm-v1.medusa.software", Environment.Prod.apiEndpoint.host)
    assertEquals(
        ClientID("329509758995-cn8lk8fcuen0u813a14m6cmfls3an24e.apps.googleusercontent.com"),
        Environment.Staging.oauthClientId,
    )
    assertEquals("api.farm-v1-staging.medusa.software", Environment.Staging.apiEndpoint.host)
  }
}
