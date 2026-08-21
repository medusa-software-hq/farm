package software.medusa.farm.shared

/**
 * A repo and the issues open on it, as one sync fetch observed them, before they are reconciled.
 */
class FetchedRepoWithIssues(
    val repo: FetchedRepo,
    val issues: List<FetchedIssue>,
)
