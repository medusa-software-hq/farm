package software.medusa.farm.shared

/**
 * An issue as observed in one sync fetch of a repo, before it is reconciled against stored rows.
 */
class FetchedIssue(
    val number: Int,
    val title: String,
)
