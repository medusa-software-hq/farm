package software.medusa.farm.worker

import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhCheckRunConclusion
import software.medusa.farm.github.GhCheckRunOutput
import software.medusa.farm.github.GhCheckRunStatus
import software.medusa.farm.github.GhInstallationApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhPullRequestReviewState
import software.medusa.farm.github.GhPullRequestState
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.shared.FarmLabels
import software.medusa.farm.shared.SessionStore

/** Runs the session writes and the GitHub comment posts on the activity thread. */
class ProcessIssueActivitiesImpl(
    private val clientProvider: GhInstallationApiClientProvider,
    private val sessionStore: SessionStore,
) : ProcessIssueActivities {
  override fun createSession(
      sessionId: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
      repoFullName: String,
      title: String,
  ) = runBlocking {
    sessionStore.create(sessionId, installationId, githubRepoId, number, repoFullName, title)
  }

  override fun postIssueComment(
      installationId: Long,
      repoFullName: String,
      number: Int,
      body: String,
  ) = runBlocking {
    clientProvider
        .provideForInstallation(GhInstallationId(installationId))
        .createIssueComment(GhRepoFullName(repoFullName), number, body)
  }

  override fun removeReadyLabel(installationId: Long, repoFullName: String, number: Int) =
      runBlocking {
        clientProvider
            .provideForInstallation(GhInstallationId(installationId))
            .removeLabel(GhRepoFullName(repoFullName), number, FarmLabels.READY)
      }

  override fun recordPullRequest(
      sessionId: String,
      number: Int,
      url: String,
      headSha: String,
  ) = runBlocking { sessionStore.recordPullRequest(sessionId, number, url, headSha) }

  override fun readPullRequest(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      afterReviewId: Long,
  ): PullRequestReport = runBlocking {
    val client = clientProvider.provideForInstallation(GhInstallationId(installationId))
    val repo = GhRepoFullName(repoFullName)
    val pullRequest = client.getPullRequest(repo, number)

    // GitHub's own merge time, not the moment this happened to notice — they differ by however
    // long the gate slept.
    pullRequest.mergedAt?.let { sessionStore.recordPullRequestMerged(sessionId, it) }

    val report =
        if (pullRequest.state != GhPullRequestState.OPEN) {
          PullRequestReport(state = pullRequest.state, feedback = null, failedChecks = emptyList())
        } else {
          PullRequestReport(
              state = pullRequest.state,
              feedback = findFeedback(client, repo, number, afterReviewId),
              failedChecks =
                  findFailedChecks(client, repo, pullRequest.baseBranch, pullRequest.headSha),
          )
        }

    report
  }

  /**
   * The oldest unacted-on review asking for changes, with what it said. Oldest rather than newest
   * so a run of reviews is worked through in the order they were written.
   */
  private suspend fun findFeedback(
      client: GhInstallationApiClient,
      repo: GhRepoFullName,
      number: Int,
      afterReviewId: Long,
  ): ReviewFeedback? {
    val review =
        client
            .listReviews(repo, number)
            .filter { it.id > afterReviewId }
            .sortedBy { it.id }
            .firstOrNull { it.state == GhPullRequestReviewState.CHANGES_REQUESTED } ?: return null

    // Only what this review said. An older review's comments were either already addressed or
    // deliberately not, and re-raising them would undo the reviewer's own judgement. Filtered from
    // the whole pull request's comments rather than asked for per review — see the client, which
    // says why the narrower endpoint is the wrong one.
    val comments =
        client
            .listReviewComments(repo, number)
            .filter { it.reviewId == review.id }
            .map { ReviewFeedbackComment(path = it.path, line = it.line, body = it.body) }

    return ReviewFeedback(reviewId = review.id, body = review.body, comments = comments)
  }

  /**
   * What the checks on [headSha] that [baseBranch] will not let a merge past came back red on, with
   * what each of them reported.
   *
   * Only those: a check the branch does not require can be red for reasons that live in the
   * infrastructure it runs on rather than in the repository, and no edit the agent makes would turn
   * it green. What blocks the merge is the part of the pipeline the agent can be held to.
   *
   * Nothing while any of them is still going: a pipeline read half way through reports whichever
   * checks happen to have finished, and reworking a pull request on the first of thirteen to fall
   * over — with the other twelve unread — is a run spent on a fraction of the problem.
   */
  private suspend fun findFailedChecks(
      client: GhInstallationApiClient,
      repo: GhRepoFullName,
      baseBranch: String,
      headSha: String,
  ): List<FailedCheck> {
    val required = client.listRequiredCheckNames(repo, baseBranch).toSet()
    val checkRuns = client.listCheckRuns(repo, headSha).filter { it.name in required }
    if (checkRuns.any { it.status != GhCheckRunStatus.COMPLETED }) return emptyList()

    return checkRuns
        .filter { it.conclusion in FAILING_CONCLUSIONS }
        // By name, so that the same failure reported twice in whatever order GitHub happens to
        // list it in is recognisable as the same failure.
        .sortedBy { it.name }
        .map { checkRun ->
          FailedCheck(
              name = checkRun.name,
              report = checkRun.output.describe(),
              annotations =
                  client.listCheckRunAnnotations(repo, checkRun.id).map {
                    FailedCheckAnnotation(path = it.path, line = it.startLine, message = it.message)
                  },
          )
        }
  }

  override fun awaitReview(sessionId: String) = runBlocking { sessionStore.awaitReview(sessionId) }

  override fun resumeWork(sessionId: String) = runBlocking { sessionStore.resumeWork(sessionId) }

  override fun completeSession(sessionId: String) = runBlocking { sessionStore.complete(sessionId) }

  override fun failSession(sessionId: String) = runBlocking { sessionStore.fail(sessionId) }

  private companion object {
    // What a branch protected by a check will not let a merge past. A cancelled run is not among
    // them: a push supersedes the checks still running on the commit before it, and the commit that
    // push made is checked in their place.
    val FAILING_CONCLUSIONS =
        setOf(
            GhCheckRunConclusion.FAILURE,
            GhCheckRunConclusion.TIMED_OUT,
            GhCheckRunConclusion.ACTION_REQUIRED,
            GhCheckRunConclusion.STARTUP_FAILURE,
        )

    /** The three boxes a check reports in, run together as the one thing it had to say. */
    fun GhCheckRunOutput.describe(): String =
        listOf(title, summary, text)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n") { it.trim() }
  }
}
