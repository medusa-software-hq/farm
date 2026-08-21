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
   * Every check run reported against commit [ref], whatever state each is in. Requires the App's
   * Checks:read permission.
   */
  suspend fun listCheckRuns(repo: GhRepoFullName, ref: String): List<GhCheckRun>

  /**
   * The names of the checks a merge into [branch] is made to wait for, as the rulesets in force on
   * it have them; empty when the branch asks nothing of a merge.
   *
   * A second question rather than a field on the check run, because whether a check has to pass is
   * a property of the branch and not of the run reporting it. Rulesets are the whole answer here: a
   * branch guarded by the older per-branch protection instead reads as requiring nothing, as does
   * one whose rules GitHub refuses to show — an unanswerable question is not a failure to ask it.
   */
  suspend fun listRequiredCheckNames(repo: GhRepoFullName, branch: String): List<String>

  /**
   * The places in the code a check run pointed at. Requires the App's Checks:read permission.
   *
   * These are the only failure text a check leaves behind that names a file: what a build wrote to
   * its own log belongs to whatever ran it, and is not on the check.
   */
  suspend fun listCheckRunAnnotations(
      repo: GhRepoFullName,
      checkRunId: GhCheckRunId,
  ): List<GhCheckAnnotation>

  /**
   * Creates a label if the repository has not got one by that name, and does nothing if it has.
   * Requires the App's Issues:write permission.
   */
  suspend fun ensureLabel(repo: GhRepoFullName, name: String)

  /**
   * Takes [name] off an issue, and does nothing if the issue was not carrying it. Requires the
   * App's Issues:write permission.
   */
  suspend fun removeLabel(repo: GhRepoFullName, number: Int, name: String)

  /** Opens an issue carrying [labels]. Requires the App's Issues:write permission. */
  suspend fun createIssue(
      repo: GhRepoFullName,
      title: String,
      body: String,
      labels: List<String>,
  ): GhIssue

  /** The repository's open pull requests, newest first. */
  suspend fun listOpenPullRequests(repo: GhRepoFullName): List<GhPullRequest>

  /** The files a pull request changes, each with the diff GitHub renders for it. */
  suspend fun listPullRequestFiles(repo: GhRepoFullName, number: Int): List<GhPullRequestFile>

  /**
   * Submits a review saying [verdict], with [body] in the review's own box and [comments] against
   * lines of the diff. Requires the App's Pull requests:write permission.
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
