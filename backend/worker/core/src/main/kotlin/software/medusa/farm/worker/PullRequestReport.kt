package software.medusa.farm.worker

import software.medusa.farm.github.GhPullRequestState

/**
 * What one look at the pull request found: where it stands, any review left to act on, and whatever
 * the checks standing between it and a merge came back red on.
 */
data class PullRequestReport(
    val state: GhPullRequestState,
    /** The newest review asking for changes that has not been acted on, if there is one. */
    val feedback: ReviewFeedback?,
    /**
     * The failing checks, in a settled order — empty both while the checks are still running and
     * when they all passed, since neither is anything to act on.
     */
    val failedChecks: List<FailedCheck>,
)
