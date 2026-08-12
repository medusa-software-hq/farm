package software.medusa.farm.shared

/** A repo as observed in one sync fetch, before it is reconciled against the stored rows. */
class FetchedRepo(
    val githubRepoId: Long,
    val fullName: String,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
)
