package software.medusa.farm.github

import java.time.Instant

/** One review of a pull request: what it said as a whole, and when. */
data class GhPullRequestReview(
    val id: Long,
    val state: GhPullRequestReviewState,
    /** What the reviewer wrote in the review's own box; empty when they wrote only on lines. */
    val body: String,
    val submittedAt: Instant,
)
