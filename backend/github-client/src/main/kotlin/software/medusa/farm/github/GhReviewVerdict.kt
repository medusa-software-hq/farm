package software.medusa.farm.github

/** What a review being submitted says about the pull request as a whole. */
enum class GhReviewVerdict {
  APPROVE,
  REQUEST_CHANGES,

  /** Says something without asking for anything. */
  COMMENT,
}
