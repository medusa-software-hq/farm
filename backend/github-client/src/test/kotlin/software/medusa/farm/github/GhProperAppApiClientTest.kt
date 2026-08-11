package software.medusa.farm.github

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GhProperAppApiClientTest {
  private val appKey = TestAppKey()
  private val clientId = "Iv1.farmtestclient"

  private fun clientAgainst(server: FakeGitHubServer): GhAppApiClient =
      GhProperAppApiClient.build(clientId, appKey.pkcs8Pem, baseUrl = server.baseUrl)

  @Test
  fun `resolves the installation id and presents a valid app JWT`() = runBlocking {
    FakeGitHubServer { request ->
          assertEquals("/orgs/acme/installation", request.pathAndQuery)
          FakeGitHubServer.Response(200, """{"id": 4242, "extra": "ignored"}""")
        }
        .use { server ->
          val id = clientAgainst(server).resolveInstallationId(GhOrgLogin("acme"))
          assertEquals(GhInstallationId(4242L), id)

          val authorization = server.requests.single().authorization
          assertNotNull(authorization)
          assertTrue(authorization.startsWith("Bearer "))
          val jwt = SignedJWT.parse(authorization.removePrefix("Bearer "))

          assertEquals("RS256", jwt.header.algorithm.name)
          assertTrue(jwt.verify(RSASSAVerifier(appKey.publicKey)))

          val claims = jwt.jwtClaimsSet
          assertEquals(clientId, claims.issuer)
          val now = Instant.now()
          val issuedAt = claims.issueTime.toInstant()
          val expiresAt = claims.expirationTime.toInstant()
          // iat is backdated ~60s; exp is ~9min out and always under GitHub's 10-minute maximum.
          assertTrue(issuedAt.isBefore(now))
          assertTrue(issuedAt.isAfter(now.minusSeconds(120)))
          assertTrue(expiresAt.isAfter(now))
          assertTrue(Duration.between(issuedAt, expiresAt) <= Duration.ofMinutes(10))
        }
  }

  @Test
  fun `mints an installation token from a 201 with its expiry`() = runBlocking {
    val expiry = "2026-08-11T12:34:56Z"
    FakeGitHubServer { request ->
          assertEquals("POST", request.method)
          assertEquals("/app/installations/4242/access_tokens", request.pathAndQuery)
          FakeGitHubServer.Response(201, """{"token": "ghs_secret", "expires_at": "$expiry"}""")
        }
        .use { server ->
          val minted = clientAgainst(server).mintInstallationToken(GhInstallationId(4242L))
          assertEquals("ghs_secret", minted.token)
          assertEquals(Instant.parse(expiry), minted.expiresAt)
        }
  }

  @Test
  fun `surfaces a non-201 mint response as a failure`() = runBlocking {
    FakeGitHubServer { FakeGitHubServer.Response(404, """{"message": "Not Found"}""") }
        .use { server ->
          assertFailsWith<IllegalStateException> {
            clientAgainst(server).mintInstallationToken(GhInstallationId(4242L))
          }
        }
    Unit
  }
}
