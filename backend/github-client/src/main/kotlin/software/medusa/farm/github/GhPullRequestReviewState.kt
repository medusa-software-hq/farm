package software.medusa.farm.github

/** What a review said about the pull request as a whole. */
enum class GhPullRequestReviewState {
  APPROVED,

  /** The reviewer asked for work before this can be merged. */
  CHANGES_REQUESTED,

  /** The reviewer said something without asking for anything — a note, a question, praise. */
  COMMENTED,
  DISMISSED,

  /** A state this library does not know by name; carried rather than guessed at. */
  UNRECOGNIZED,
}
