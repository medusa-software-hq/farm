package software.medusa.farm.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI
import java.text.ParseException

private const val httpAuthorizationHeaderName = "Authorization"
private const val bearerPrefix = "Bearer "

private const val googleAccountsHostname = "accounts.google.com"

private val googleJwksUri = URI("https://www.googleapis.com/oauth2/v3/certs").toURL()
private val googleIssuers = setOf("https://$googleAccountsHostname", googleAccountsHostname)

/**
 * Verifies a Google ID token passed as `Authorization: Bearer <token>`.
 *
 * Checks:
 * - Valid signature against Google's JWKS
 * - `iss` is a known Google issuer
 * - `aud` is one of [allowedAudiences] (the web SPA client and, optionally, the CLI Desktop client
 *   — these are distinct OAuth clients, both minted by the same organization)
 * - Token is not expired
 * - `hd` claim matches [allowedDomain] — the hosted-domain claim is what keeps out any token whose
 *   audience happens to match but whose subject isn't in this Workspace
 *
 * Returns HTTP 401 on any failure.
 */
class GoogleIdTokenAuthDecorator(
    private val allowedAudiences: Set<String>,
    private val allowedDomain: String,
) : DecoratingHttpServiceFunction {
  companion object {
    private val unauthorized: HttpResponse
      get() = HttpResponse.of(HttpStatus.UNAUTHORIZED)
  }

  private val jwtProcessor = buildJwtProcessor()

  private fun buildJwtProcessor(): DefaultJWTProcessor<SecurityContext> {
    val jwkSource =
        JWKSourceBuilder.create<SecurityContext>(googleJwksUri).refreshAheadCache(true).build()

    val keySelector = JWSVerificationKeySelector(com.nimbusds.jose.JWSAlgorithm.RS256, jwkSource)

    // Audience is verified manually below: Nimbus's DefaultJWTClaimsVerifier can only exact-match a
    // single audience, but we accept any of a set (web + CLI clients).
    val claimsVerifier =
        DefaultJWTClaimsVerifier<SecurityContext>(
            com.nimbusds.jwt.JWTClaimsSet.Builder().build(),
            setOf("sub", "email", "iat", "exp"),
        )

    return DefaultJWTProcessor<SecurityContext>().apply {
      jwsKeySelector = keySelector
      jwtClaimsSetVerifier = claimsVerifier
    }
  }

  override fun serve(
      delegate: HttpService,
      ctx: ServiceRequestContext,
      req: HttpRequest,
  ): HttpResponse {
    val token = extractBearerToken(req) ?: return unauthorized

    val claims =
        try {
          jwtProcessor.process(token, null)
        } catch (_: ParseException) {
          // Malformed / non-JWT token.
          return unauthorized
        } catch (_: BadJOSEException) {
          // Bad signature or failed claims verification.
          return unauthorized
        }
    // Anything else (e.g. RemoteKeySourceException when Google's JWKS is unreachable) is NOT the
    // client's fault — let it propagate to a 500 rather than masquerade as a 401.

    // Verify issuer manually (nimbus claimsVerifier checks exp/required fields).
    if (claims.issuer !in googleIssuers) return unauthorized

    // Accept a token minted by any of our OAuth clients (web SPA or CLI Desktop client).
    if ((claims.audience ?: emptyList()).none { it in allowedAudiences }) return unauthorized

    // Enforce hosted domain.
    val hd = claims.getStringClaim("hd")
    if (hd != allowedDomain) return unauthorized

    return delegate.serve(ctx, req)
  }

  private fun extractBearerToken(req: HttpRequest): String? {
    val header = req.headers().get(httpAuthorizationHeaderName) ?: return null
    if (!header.startsWith(bearerPrefix)) return null
    return header.removePrefix(bearerPrefix).trim().takeIf { it.isNotEmpty() }
  }
}
