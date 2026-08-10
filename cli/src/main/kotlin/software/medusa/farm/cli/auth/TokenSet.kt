package software.medusa.farm.cli.auth

import com.nimbusds.oauth2.sdk.token.RefreshToken
import kotlin.time.Instant

/** A Google ID token plus the refresh token and the moment the ID token expires. */
data class TokenSet(val idToken: String, val refreshToken: RefreshToken?, val expiresAt: Instant)
