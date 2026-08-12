package software.medusa.farm.shared

/** A stored processing session, tied to the issue (github_repo_id, number) it is working. */
class Session(
    val id: String,
    val installationId: Long,
    val githubRepoId: Long,
    val number: Int,
    val state: SessionState,
)
