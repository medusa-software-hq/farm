package software.medusa.farm.worker

import software.medusa.farm.github.GhPullRequestState

/** What one look at the pull request found: where it stands, and any review left to act on. */
data class PullRequestReport(
    val state: GhPullRequestState,
    /** The newest review asking for changes that has not been acted on, if there is one. */
    val feedback: ReviewFeedback?,
)
