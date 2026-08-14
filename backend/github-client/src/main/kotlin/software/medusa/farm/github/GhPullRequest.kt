package software.medusa.farm.github

/** A pull request as Farm tracks it: its number, web [url], current [state], and head commit. */
data class GhPullRequest(
    val number: Int,
    val url: String,
    val state: GhPullRequestState,
    val headSha: String,
)
