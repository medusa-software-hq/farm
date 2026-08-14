package software.medusa.farm.worker

/**
 * The result of attempting an issue: the pull request that was opened, or all-`null` when the agent
 * ran cleanly but produced no changes to publish.
 */
data class IssueAttemptOutcome(
    val pullRequestUrl: String?,
    val pullRequestNumber: Int?,
    val pullRequestHeadSha: String?,
)
