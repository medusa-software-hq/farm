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

  /**
   * Every comment left on a line of the pull request's diff, across all of its reviews; which
   * review left one is on the comment itself.
   *
   * Across all of them rather than per review on purpose. GitHub does serve one review's comments
   * directly, but that representation carries no `line` — only the diff-hunk `position` — so asking
   * the narrower question gets a thinner answer, and where a comment actually points is lost.
   */
  suspend fun listReviewComments(
      repo: GhRepoFullName,
      number: Int,
  ): List<GhPullRequestReviewComment>

  /**
   * Creates a label if the repository has not got one by that name, and does nothing if it has.
   * Requires the App's Issues:write permission.
   */
  suspend fun ensureLabel(repo: GhRepoFullName, name: String)

  /** Opens an issue carrying [labels]. Requires the App's Issues:write permission. */
  suspend fun createIssue(
      repo: GhRepoFullName,
      title: String,
      body: String,
      labels: List<String>,
  ): GhIssue

  /** The repository's open pull requests, newest first. */
  suspend fun listOpenPullRequests(repo: GhRepoFullName): List<GhPullRequest>

  /** The paths a pull request changes. */
  suspend fun listPullRequestPaths(repo: GhRepoFullName, number: Int): List<String>

  /**
   * Submits a review saying [verdict], with [body] in the review's own box and [comments] against
   * files. Requires the App's Pull requests:write permission.
   *
   * GitHub will not accept a verdict of [GhReviewVerdict.APPROVE] or
   * [GhReviewVerdict.REQUEST_CHANGES] on a pull request the same App opened.
   */
  suspend fun createReview(
      repo: GhRepoFullName,
      number: Int,
      verdict: GhReviewVerdict,
      body: String,
      comments: List<GhNewReviewComment>,
  )

  /** Merges a pull request. Requires the App's Pull requests:write permission. */
  suspend fun mergePullRequest(repo: GhRepoFullName, number: Int, method: GhMergeMethod)

  /**
   * Creates [name] in [owner] from [template], with the template's files and none of its issues,
   * labels or history. Requires the App's Administration:write permission on the owner.
   */
  suspend fun createRepositoryFromTemplate(
      template: GhRepoFullName,
      owner: String,
      name: String,
      isPrivate: Boolean,
  ): GhRepo

  /** Deletes a repository. Requires the App's Administration:write permission. */
  suspend fun deleteRepository(repo: GhRepoFullName)
}
