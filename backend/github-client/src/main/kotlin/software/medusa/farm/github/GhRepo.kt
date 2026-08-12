package software.medusa.farm.github

/** A repository the app can reach, with the fields a sync persists. */
class GhRepo(
    val id: GhRepoId,
    val fullName: GhRepoFullName,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
)
