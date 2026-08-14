package software.medusa.farm.worker

/**
 * The result of attempting an issue: the URL of the pull request that was opened, or `null` when
 * the agent ran cleanly but produced no changes to publish.
 */
data class IssueAttemptOutcome(
    val pullRequestUrl: String?,
)
