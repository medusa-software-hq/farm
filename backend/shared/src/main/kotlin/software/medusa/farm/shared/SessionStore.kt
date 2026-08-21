package software.medusa.farm.shared

import java.time.Instant

/** The processing sessions Farm opens against issues. */
interface SessionStore {
  /**
   * Opens a running session with the given id, recording the issue it works. Idempotent on the id
   * (safe under activity retry).
   */
  suspend fun create(
      id: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
      repoFullName: String,
      title: String,
  )

  /**
   * Marks the session as waiting on somebody to review the pull request it opened: still live, but
   * with nothing left for Farm to do until a person says something. Idempotent.
   */
  suspend fun awaitReview(id: String)

  /** Marks the session as working again, after [awaitReview]. Idempotent. */
  suspend fun resumeWork(id: String)

  /** Marks the session completed. */
  suspend fun complete(id: String)

  /** Marks the session failed. */
  suspend fun fail(id: String)

  /** Records the pull request the session opened. Idempotent on the session id. */
  suspend fun recordPullRequest(id: String, number: Int, url: String, headSha: String)

  /**
   * Records that the session's pull request was merged, at the time GitHub says it was. Idempotent.
   */
  suspend fun recordPullRequestMerged(id: String, mergedAt: Instant)

  /**
   * Opens try [attempt] at run [ordinal] of session [id], ready to be appended to. Idempotent on
   * ([id], [ordinal], [attempt]): the same try running again starts over, while the tries before it
   * are left as they were.
   */
  suspend fun startRunAttempt(id: String, ordinal: Int, attempt: Int)

  /**
   * Appends [entry] at [position], counting from zero, to try [attempt] at run [ordinal] of session
   * [id]. Idempotent on ([id], [ordinal], [attempt], [position]).
   */
  suspend fun appendRunEntry(
      id: String,
      ordinal: Int,
      attempt: Int,
      position: Int,
      entry: AgentRunEntry,
  )

  /** Closes try [attempt] at run [ordinal] of session [id] with how it went. */
  suspend fun finishRunAttempt(
      id: String,
      ordinal: Int,
      attempt: Int,
      outcome: AgentRunOutcome,
      cost: AgentRunCost?,
      summary: String,
  )

  /** The session's agent runs, ordered by [SessionRun.ordinal]. */
  suspend fun getRuns(id: String): List<SessionRun>

  suspend fun listForOrgs(installationIds: List<Long>): List<Session>

  suspend fun get(id: String): Session?
}
