package software.medusa.farm.cli.auth

import com.nimbusds.oauth2.sdk.AuthorizationResponse
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.OIDCScopeValue
import com.nimbusds.openid.connect.sdk.Prompt
import java.net.URI
import java.time.Duration

/** How long the loopback receiver waits for Google's browser redirect before giving up. */
private val CALLBACK_TIMEOUT: Duration = Duration.ofMinutes(5)

// Google-proprietary authorization parameter — NOT part of OAuth 2.0 or OpenID Connect. Requesting
// "offline" access is how Google is told to issue a refresh token, so the CLI can mint fresh ID
// tokens without a browser. Combined with the standard OIDC prompt=consent (below), it makes Google
// return a refresh token reliably — not only on the user's very first consent.
// https://developers.google.com/identity/protocols/oauth2/web-server#offline
private const val googleAccessTypeParam = "access_type"
private const val googleAccessTypeOffline = "offline"

/**
 * The interactive Google sign-in: authorization-code + PKCE over a localhost loopback (RFC 8252).
 * Opens the browser to Google's consent screen, catches the redirect, and exchanges the code for
 * tokens via [tokenClient]. The OAuth/OIDC protocol (auth request, PKCE, callback parsing) is the
 * Nimbus SDK's; only the loopback and the browser launch are ours. [echo] reports progress.
 */
fun googleSignIn(
    authEndpoint: URI,
    clientId: ClientID,
    tokenClient: OAuthTokenClient,
    browserOpener: BrowserOpener,
    echo: (String) -> Unit,
): TokenSet {
  val codeVerifier = CodeVerifier()
  val state = State()
  LoopbackReceiver().use { receiver ->
    val authUrl =
        googleAuthorizationUrl(authEndpoint, clientId, receiver.redirectUri, codeVerifier, state)
    echo("Opening your browser to sign in…")
    if (!browserOpener.open(authUrl)) {
      echo("Couldn't open a browser automatically. Open this URL to continue:\n$authUrl")
    }
    val response = AuthorizationResponse.parse(receiver.awaitCallback(CALLBACK_TIMEOUT))
    if (!response.indicatesSuccess()) {
      val error = response.toErrorResponse().errorObject
      throw OAuthException(error.code ?: "oauth_error", error.description)
    }
    val success = response.toSuccessResponse()
    if (success.state != state) {
      throw OAuthException("state_mismatch", "OAuth state did not match; aborting.")
    }
    val code =
        success.authorizationCode
            ?: throw OAuthException("no_code", "No authorization code returned.")
    return tokenClient.exchangeAuthorizationCode(code, codeVerifier, receiver.redirectUri)
  }
}

private fun googleAuthorizationUrl(
    authEndpoint: URI,
    clientId: ClientID,
    redirectUri: URI,
    codeVerifier: CodeVerifier,
    state: State,
): URI =
    AuthenticationRequest.Builder(
            ResponseType(ResponseType.Value.CODE), // OAuth 2.0 authorization code grant (RFC 6749)
            Scope(OIDCScopeValue.OPENID, OIDCScopeValue.EMAIL), // OIDC scopes (OIDC Core §5.4)
            clientId,
            redirectUri,
        )
        .endpointURI(authEndpoint)
        .state(state)
        .codeChallenge(codeVerifier, CodeChallengeMethod.S256) // PKCE (RFC 7636)
        // Force re-consent so Google reliably returns a refresh token — standard OIDC parameter
        // (OIDC Core §3.1.2.1), unlike access_type below.
        .prompt(Prompt(Prompt.Type.CONSENT))
        .customParameter(googleAccessTypeParam, googleAccessTypeOffline)
        .build()
        .toURI()
