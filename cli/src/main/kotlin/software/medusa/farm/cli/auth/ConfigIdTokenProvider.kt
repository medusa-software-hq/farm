package software.medusa.farm.cli.auth

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import software.medusa.farm.cli.config.ConfigStore
import software.medusa.farm.cli.config.Credentials

/**
 * An [IdTokenProvider] backed by the cached sign-in in [ConfigStore]. Loaded once via [load] (which
 * returns null when there is no cached session at all — the caller turns that into a "run login"
 * hint), it hands back the cached ID token while it's still valid and silently refreshes it — no
 * browser, updating both its in-memory copy and the on-disk file — when it's expired. Only a
 * revoked/expired refresh token forces a fresh `ms-farm login`.
 */
class ConfigIdTokenProvider
private constructor(
    private val clock: Clock,
    private val configStore: ConfigStore,
    private val tokenRefresher: OAuthTokenRefresher,
    initialCredentials: Credentials,
) : IdTokenProvider {
  private var credentials = initialCredentials

  override fun provideFreshIdToken(): String {
    // Refresh a little early so a token doesn't expire mid-request.
    if (credentials.idTokenExpiresAt > clock.now() + REFRESH_SKEW) {
      return credentials.idToken
    }

    val refreshed =
        try {
          tokenRefresher.refresh(credentials.refreshToken)
        } catch (e: OAuthException) {
          throw NotLoggedInException("Sign-in expired (${e.code}). Run 'ms-farm login' again.")
        }

    credentials =
        credentials.copy(idToken = refreshed.idToken, idTokenExpiresAt = refreshed.expiresAt)
    configStore.saveCredentials(credentials)
    return refreshed.idToken
  }

  companion object {
    /** Loads the cached sign-in; null when there is none (the caller reports "run login"). */
    fun load(
        clock: Clock,
        configStore: ConfigStore,
        tokenRefresher: OAuthTokenRefresher,
    ): ConfigIdTokenProvider? {
      val credentials = configStore.loadCredentials() ?: return null
      return ConfigIdTokenProvider(clock, configStore, tokenRefresher, credentials)
    }

    // Refresh this far ahead of expiry so a token can't lapse mid-request.
    private val REFRESH_SKEW = 30.seconds
  }
}
