package software.medusa.farm.server

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.auth.AuthTokenExtractors
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI
import java.text.ParseException

// Google's OpenID Connect endpoints, from its OIDC discovery document
// (https://accounts.google.com/.well-known/openid-configuration):
//   jwks_uri = https://www.googleapis.com/oauth2/v3/certs
//   issuer   = https://accounts.google.com
// Pinned rather than fetched at runtime — these change very rarely.
// Docs: https://developers.google.com/identity/openid-connect/openid-connect#discovery
private const val googleAccountsHostname = "accounts.google.com"
private val googleJwksUri = URI("https://www.googleapis.com/oauth2/v3/certs").toURL()
// Google emits `iss` as either the bare host or the https URL — accept both.
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
    private val logger = org.slf4j.LoggerFactory.getLogger(GoogleIdTokenAuthDecorator::class.java)

    // No usable credential was presented (missing/empty/non-Bearer). Per RFC 6750 §3.1,
    // a request that carries no token gets a bare challenge with no error code.
    private val missingCredential: HttpResponse
      get() =
          HttpResponse.of(
              ResponseHeaders.builder(HttpStatus.UNAUTHORIZED)
                  .add(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer")
                  .build()
          )

    // A token was presented but rejected (unparseable / bad signature / bad claims / wrong
    // audience / wrong hosted-domain). RFC 6750 error="invalid_token" — deliberately one
    // coarse bucket so we don't leak which validation step failed.
    private val invalidToken: HttpResponse
      get() =
          HttpResponse.of(
              ResponseHeaders.builder(HttpStatus.UNAUTHORIZED)
                  .add(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                  .build()
          )
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
    val token = extractBearerToken(req)
    if (token == null) {
      logger.debug("Rejecting {} {}: no bearer credential presented", req.method(), req.path())
      return missingCredential
    }

    val claims =
        try {
          jwtProcessor.process(token, null)
        } catch (_: ParseException) {
          // Malformed / non-JWT token.
          return invalidToken
        } catch (_: BadJOSEException) {
          // Bad signature or failed claims verification.
          return invalidToken
        }
    // Anything else (e.g. RemoteKeySourceException when Google's JWKS is unreachable) is NOT the
    // client's fault — let it propagate to a 500 rather than masquerade as a 401.

    // Verify issuer manually (nimbus claimsVerifier checks exp/required fields).
    if (claims.issuer !in googleIssuers) return invalidToken

    // Accept a token minted by any of our OAuth clients (web SPA or CLI Desktop client).
    if ((claims.audience ?: emptyList()).none { it in allowedAudiences }) return invalidToken

    // Enforce hosted domain.
    val hd = claims.getStringClaim("hd")
    if (hd != allowedDomain) return invalidToken

    return delegate.serve(ctx, req)
  }

  private fun extractBearerToken(req: HttpRequest): String? =
      AuthTokenExtractors.oAuth2().apply(req.headers())?.accessToken()?.takeIf { it.isNotEmpty() }
}
