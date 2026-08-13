package software.medusa.farm.shared

/**
 * An issue as observed in one sync fetch of a repo, before it is reconciled against stored rows.
 * [isReady] is whether it carries the [FarmLabels.READY] label — the gate for auto-processing; the
 * issue is synced and listed regardless.
 */
class FetchedIssue(
    val number: Int,
    val title: String,
    val isReady: Boolean,
)
