package software.medusa.farm.github

/** One comment a review left on a line of the diff. */
data class GhPullRequestReviewComment(
    /** The review this comment was part of. */
    val reviewId: Long,
    val path: String,
    /** The line it is against, or null where the comment outlived the line it was written on. */
    val line: Int?,
    val body: String,
)
