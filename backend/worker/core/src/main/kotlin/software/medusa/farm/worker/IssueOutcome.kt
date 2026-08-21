package software.medusa.farm.worker

/** How Farm's session on an issue ended, which is what the issue's closing comment reports. */
enum class IssueOutcome {
  /** The pull request was merged: the issue is done. */
  MERGED,

  /** The pull request was closed without being merged. */
  CLOSED_UNMERGED,

  /** Nobody touched the pull request for long enough that Farm stopped following it. */
  ABANDONED,

  /** The agent ran and found nothing to change, so there was no pull request to open. */
  NOTHING_TO_CHANGE,

  /** The run itself broke, so the issue was never worked to a conclusion. */
  FAILED,
}
