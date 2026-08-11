package software.medusa.farm.github

import java.time.Instant

/** A minted installation access token and the instant GitHub says it expires. */
class MintedGhInstallationToken(
    val token: String,
    val expiresAt: Instant,
)
