package software.medusa.farm.cli

import java.nio.file.Path

/** Raised when there's no usable session — the caller turns it into a "run ms-farm login" hint. */
class NotLoggedInException(message: String) : Exception(message)

/**
 * The default token refresher for [env]: mints a fresh ID token via that environment's OAuth client
 * (id + the secret this build resolves for it).
 */
internal fun defaultRefresher(env: Environment): (String) -> TokenSet = { refreshToken ->
  val secret =
      env.oauthClientSecret
          ?: throw NotLoggedInException(
              "This CLI build has no OAuth client secret for ${env.label}; set " +
                  "${env.oauthClientSecretEnvVar}."
          )
  CounterOAuth(clientId = env.oauthClientId, clientSecret = secret).refresh(refreshToken)
}

/**
 * Supplies a currently-valid Google ID token for API calls: hands back the cached one while it's
 * still good, and silently refreshes it (no browser) when it's expired. Only a revoked/expired
 * refresh token forces a fresh `ms-farm login`. Environment-agnostic by construction — the caller
 * passes the environment's [dir] and its [refresher] (see [defaultRefresher]).
 */
class Session(
    private val dir: Path,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val refresher: (String) -> TokenSet,
) {
  fun currentIdToken(): String {
    val credentials =
        loadCredentials(dir)
            ?: throw NotLoggedInException("Not signed in. Run 'ms-farm login' first.")

    // Refresh a little early so a token doesn't expire mid-request.
    if (credentials.idTokenExpiresAtEpochSec > nowEpochSec() + 30) {
      return credentials.idToken
    }

    val refreshed =
        try {
          refresher(credentials.refreshToken)
        } catch (e: OAuthException) {
          throw NotLoggedInException("Session expired (${e.code}). Run 'ms-farm login' again.")
        }

    saveCredentials(
        credentials.copy(
            idToken = refreshed.idToken,
            idTokenExpiresAtEpochSec = refreshed.expiresAtEpochSec,
        ),
        dir,
    )
    return refreshed.idToken
  }
}
