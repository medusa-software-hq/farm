package software.medusa.farm.cli.config

import com.nimbusds.oauth2.sdk.token.RefreshToken
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cached sign-in: the long-lived refresh token plus the most recent ID token and when it expires.
 * Loaded and persisted (file 0600, dir 0700) via [ConfigStore]. The refresh token is the sensitive
 * field — it stands in for the human until revoked. The expiry is kept on disk as epoch seconds
 * (its historical wire format) but is a typed [Instant] in code; the refresh token is a typed
 * Nimbus [RefreshToken] persisted as its raw string.
 */
@Serializable
data class Credentials(
    @Serializable(with = RefreshTokenSerializer::class) val refreshToken: RefreshToken,
    val idToken: String,
    @SerialName("idTokenExpiresAtEpochSec")
    @Serializable(with = InstantEpochSecondsSerializer::class)
    val idTokenExpiresAt: Instant,
    val email: String,
)
