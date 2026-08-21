package software.medusa.farm.github

import java.time.Instant

/** A pull request as Farm tracks it: its number, web [url], current [state], and head commit. */
data class GhPullRequest(
    val number: Int,
    val url: String,
    val state: GhPullRequestState,
    val headSha: String,
    /** The branch it is asking to merge into, whose rules say what it has to satisfy first. */
    val baseBranch: String,
    /** When it was merged, as GitHub recorded it; null unless [state] is merged. */
    val mergedAt: Instant?,
)
