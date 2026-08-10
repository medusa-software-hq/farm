package software.medusa.farm.cli.auth

import com.nimbusds.jwt.JWTParser
import kotlin.time.Instant

/**
 * The claims we read out of a Google ID token *we already trust* — it came straight from Google's
 * token endpoint over TLS, so this is not a verification path (the API re-verifies the signature).
 * [parse] reads only the claims and never validates; any unreadable claim comes back null.
 */
data class JwtToken(val email: String?, val expiresAt: Instant?) {
  companion object {
    /** Reads the claims from [idToken]; a malformed token or claim yields nulls. */
    fun parse(idToken: String): JwtToken {
      val claims =
          runCatching { JWTParser.parse(idToken).jwtClaimsSet }.getOrNull()
              ?: return JwtToken(null, null)
      return JwtToken(
          email = runCatching { claims.getStringClaim("email") }.getOrNull(),
          expiresAt =
              runCatching { claims.expirationTime?.let { Instant.fromEpochMilliseconds(it.time) } }
                  .getOrNull(),
      )
    }
  }
}
