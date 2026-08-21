package software.medusa.farm.cli.api

/**
 * An open issue known to Farm: its repo's full name, number, title, and its latest processing
 * session's state ("", "RUNNING", "AWAITING_REVIEW", "COMPLETED", or "FAILED").
 */
class IssueResult(
    val repoFullName: String,
    val number: Int,
    val title: String,
    val sessionState: String,
)
