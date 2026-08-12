package software.medusa.farm.cli.api

/** An open issue known to Farm: its repo's full name, its number, and its title. */
class IssueResult(
    val repoFullName: String,
    val number: Int,
    val title: String,
)
