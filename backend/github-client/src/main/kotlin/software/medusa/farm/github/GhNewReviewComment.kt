package software.medusa.farm.github

/**
 * A comment to leave on a file in a review.
 *
 * Against the file rather than a line of it: a line comment has to land inside the diff, so leaving
 * one means knowing what the diff contains and where.
 */
data class GhNewReviewComment(
    val path: String,
    val body: String,
)
