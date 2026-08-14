package software.medusa.farm.github

/** Where a pull request stands: still open, merged, or closed without merging. */
enum class GhPullRequestState {
  OPEN,
  MERGED,
  CLOSED,
}
