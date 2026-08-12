package software.medusa.farm.shared

/**
 * A stored issue row, keyed by its repo and GitHub's per-repo issue number. Carries enough repo
 * context (installation + full name) to act on the issue without a join.
 */
class Issue(
    val githubRepoId: Long,
    val installationId: Long,
    val repoFullName: String,
    val number: Int,
    val title: String,
)
