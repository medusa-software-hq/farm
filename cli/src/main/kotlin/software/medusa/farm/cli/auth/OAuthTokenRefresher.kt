package software.medusa.farm.cli.auth

import com.nimbusds.oauth2.sdk.token.RefreshToken

/** Mints a fresh [TokenSet] from a stored refresh token (no browser). */
interface OAuthTokenRefresher {
  fun refresh(refreshToken: RefreshToken): TokenSet
}
