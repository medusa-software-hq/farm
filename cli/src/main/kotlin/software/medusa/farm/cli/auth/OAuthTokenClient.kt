package software.medusa.farm.cli.auth

import com.nimbusds.jwt.JWT
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.AuthorizationGrant
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.RefreshTokenGrant
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.oauth2.sdk.token.RefreshToken
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import java.io.IOException
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The OAuth 2.0 / OpenID Connect token endpoint (RFC 6749 §3.2) for one configured client, backed
 * by the Nimbus SDK. Transport + protocol only — no browser, no interactive flow: it turns a grant
 * into a [TokenSet], raising [OAuthException] for an OAuth error response or a transport failure.
 */
class OAuthTokenClient(
    private val tokenEndpoint: URI,
    private val clientId: ClientID,
    private val clientSecret: Secret,
) : OAuthTokenRefresher {
  /** Exchanges an authorization code (with its PKCE verifier) for tokens. */
  fun exchangeAuthorizationCode(
      code: AuthorizationCode,
      codeVerifier: CodeVerifier,
      redirectUri: URI,
  ): TokenSet = send(AuthorizationCodeGrant(code, redirectUri, codeVerifier))

  /** Mints a fresh ID token from a stored refresh token (no browser). */
  override fun refresh(refreshToken: RefreshToken): TokenSet = send(RefreshTokenGrant(refreshToken))

  private fun send(grant: AuthorizationGrant): TokenSet {
    val request =
        TokenRequest.Builder(tokenEndpoint, ClientSecretPost(clientId, clientSecret), grant).build()
    val httpResponse =
        try {
          request.toHTTPRequest().send()
        } catch (e: IOException) {
          throw OAuthException("network_error", e.message)
        }
    val response =
        try {
          OIDCTokenResponseParser.parse(httpResponse)
        } catch (e: ParseException) {
          throw OAuthException("invalid_response", e.message)
        }
    if (!response.indicatesSuccess()) {
      val error = response.toErrorResponse().errorObject
      throw OAuthException(error.code ?: "oauth_error", error.description)
    }
    val tokens = (response.toSuccessResponse() as OIDCTokenResponse).oidcTokens
    val idToken = tokens.idTokenString ?: throw OAuthException("no_id_token", null)
    return TokenSet(idToken, tokens.refreshToken, expiresAt(tokens.idToken))
  }

  /** The ID token's `exp`, or a conservative default from now if it can't be read. */
  private fun expiresAt(idToken: JWT?): Instant =
      runCatching {
            idToken?.jwtClaimsSet?.expirationTime?.let { Instant.fromEpochMilliseconds(it.time) }
          }
          .getOrNull() ?: (Clock.System.now() + DEFAULT_LIFETIME)

  companion object {
    private val DEFAULT_LIFETIME = 1.hours
  }
}
