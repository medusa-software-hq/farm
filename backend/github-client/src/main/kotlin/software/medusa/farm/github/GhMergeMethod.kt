package software.medusa.farm.github

/** How a pull request's commits reach the base branch. */
enum class GhMergeMethod {
  MERGE,
  SQUASH,
  REBASE;

  internal val wireValue: String
    get() = name.lowercase()
}
