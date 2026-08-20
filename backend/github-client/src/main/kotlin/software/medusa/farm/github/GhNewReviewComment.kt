package software.medusa.farm.github

/**
 * A comment to leave against [line] of [path] in a review, numbered as in the head revision.
 *
 * GitHub takes a review comment only against a line the pull request's diff contains, so a line it
 * adds is the one to reach for — see [GhPullRequestFile.findFirstAddedLine].
 */
data class GhNewReviewComment(
    val path: String,
    val line: Int,
    val body: String,
)
