package software.medusa.farm.shared

/** A stored repo row, keyed by its installation and GitHub's stable numeric id. */
class Repo(
    val githubRepoId: Long,
    val installationId: Long,
    val fullName: String,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
)
