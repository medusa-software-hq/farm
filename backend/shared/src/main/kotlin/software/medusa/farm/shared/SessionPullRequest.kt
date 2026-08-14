package software.medusa.farm.shared

import java.time.Instant

/**
 * The pull request a session opened. [mergedAt] is null until it merges (filled by the merge gate).
 */
data class SessionPullRequest(
    val number: Int,
    val url: String,
    val headSha: String,
    val mergedAt: Instant?,
)
