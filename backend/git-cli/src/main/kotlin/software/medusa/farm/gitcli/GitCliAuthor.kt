package software.medusa.farm.gitcli

/** The name and email recorded on a commit (the clone carries none of its own). */
data class GitCliAuthor(
    val name: String,
    val email: String,
)
