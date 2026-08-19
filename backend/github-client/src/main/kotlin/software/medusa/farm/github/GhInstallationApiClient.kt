package software.medusa.farm.github

/** The resource surface plus the endpoints reachable only with an installation token. */
interface GhInstallationApiClient : GhResourcesApiClient {
  suspend fun listInstallationRepositories(): List<GhRepo>

  /** Posts a comment on an issue. Requires the App's Issues:write permission. */
  suspend fun createIssueComment(repo: GhRepoFullName, number: Int, body: String)

  /**
   * Opens a pull request from [head] into [base] and returns it. Requires the App's Pull
   * requests:write permission.
   */
  suspend fun createPullRequest(
      repo: GhRepoFullName,
      head: String,
      base: String,
      title: String,
      body: String,
  ): GhPullRequest

  /** The current state of pull request [number]. */
  suspend fun getPullRequest(repo: GhRepoFullName, number: Int): GhPullRequest

  /** The pull request's reviews, oldest first. */
  suspend fun listReviews(repo: GhRepoFullName, number: Int): List<GhPullRequestReview>

  /** Every comment left on a line of the pull request's diff, across all of its reviews. */
  suspend fun listReviewComments(
      repo: GhRepoFullName,
      number: Int,
  ): List<GhPullRequestReviewComment>
}
