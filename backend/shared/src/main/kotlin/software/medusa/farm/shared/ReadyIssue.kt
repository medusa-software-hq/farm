package software.medusa.farm.shared

/** An issue a sync found asking to be worked, named by everything processing it needs. */
class ReadyIssue(
    val githubRepoId: Long,
    val repoFullName: String,
    val number: Int,
    val title: String,
)
