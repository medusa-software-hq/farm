package software.medusa.farm.shared

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [SessionStore]. The [clock] supplies session timestamps; insertion order is preserved
 * so the "latest session per issue" the issues read relies on stays correct.
 */
class InMemorySessionStore(private val clock: Clock) : SessionStore {
  private val rows = ConcurrentHashMap<String, Session>()
  private val prs = ConcurrentHashMap<String, SessionPullRequest>()
  private val runs =
      ConcurrentHashMap<String, MutableMap<Int, MutableMap<Int, SessionRunAttempt>>>()
  private val order = java.util.Collections.synchronizedList(mutableListOf<String>())

  override suspend fun create(
      id: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
      repoFullName: String,
      title: String,
  ) {
    val now = clock.instant()
    val session =
        Session(
            id = id,
            installationId = installationId,
            githubRepoId = githubRepoId,
            number = number,
            repoFullName = repoFullName,
            title = title,
            state = SessionState.RUNNING,
            startedAt = now,
            finishedAt = null,
            pullRequest = null,
        )
    // Idempotent on the id, mirroring the DB's ON CONFLICT DO NOTHING.
    if (rows.putIfAbsent(id, session) == null) {
      order += id
    }
  }

  override suspend fun complete(id: String) {
    transition(id, SessionState.COMPLETED)
  }

  override suspend fun fail(id: String) {
    transition(id, SessionState.FAILED)
  }

  override suspend fun recordPullRequest(id: String, number: Int, url: String, headSha: String) {
    prs[id] = SessionPullRequest(number = number, url = url, headSha = headSha, mergedAt = null)
  }

  override suspend fun startRunAttempt(id: String, ordinal: Int, attempt: Int) {
    attempts(id, ordinal)[attempt] =
        SessionRunAttempt.Running(
            number = attempt,
            log = AgentRunLog(entries = emptyList()),
            startedAt = clock.instant(),
        )
  }

  override suspend fun appendRunEntry(
      id: String,
      ordinal: Int,
      attempt: Int,
      position: Int,
      entry: AgentRunEntry,
  ) {
    val current = openAttempt(id, ordinal, attempt)
    val entries = current.log.entries.toMutableList()
    // Addressed by position rather than appended blindly, so writing the same one twice — which a
    // retried write is entitled to do — leaves the log as it was.
    if (position < entries.size) entries[position] = entry else entries.add(entry)
    attempts(id, ordinal)[attempt] =
        SessionRunAttempt.Running(
            number = attempt,
            log = AgentRunLog(entries = entries),
            startedAt = current.startedAt,
        )
  }

  override suspend fun finishRunAttempt(
      id: String,
      ordinal: Int,
      attempt: Int,
      outcome: AgentRunOutcome,
      cost: AgentRunCost?,
      summary: String,
  ) {
    val current = openAttempt(id, ordinal, attempt)
    attempts(id, ordinal)[attempt] =
        SessionRunAttempt.Finished(
            number = attempt,
            log = current.log,
            outcome = outcome,
            cost = cost,
            summary = summary,
            startedAt = current.startedAt,
        )
  }

  override suspend fun getRuns(id: String): List<SessionRun> =
      runs[id]
          .orEmpty()
          .entries
          .sortedBy { it.key }
          .map { (ordinal, byAttempt) ->
            val ordered = byAttempt.values.sortedBy { it.number }
            SessionRun(
                ordinal = ordinal,
                attempts =
                    ordered.mapIndexed { index, attempt ->
                      // Rows arrive in attempt order, so anything unfinished before the last one
                      // has been superseded and is not still going.
                      if (attempt is SessionRunAttempt.Running && index != ordered.lastIndex) {
                        SessionRunAttempt.Abandoned(
                            number = attempt.number,
                            log = attempt.log,
                            startedAt = attempt.startedAt,
                        )
                      } else {
                        attempt
                      }
                    },
            )
          }

  private fun attempts(id: String, ordinal: Int): MutableMap<Int, SessionRunAttempt> =
      runs.getOrPut(id) { ConcurrentHashMap() }.getOrPut(ordinal) { ConcurrentHashMap() }

  private fun openAttempt(id: String, ordinal: Int, attempt: Int): SessionRunAttempt =
      runs[id]?.get(ordinal)?.get(attempt)
          ?: error("try $attempt at run $ordinal of session $id was never started")

  private fun transition(id: String, state: SessionState) {
    val now = clock.instant()
    rows.computeIfPresent(id) { _, s -> s.copy(state = state, finishedAt = now) }
  }

  override suspend fun listForOrgs(installationIds: List<Long>): List<Session> =
      order.mapNotNull { withPullRequest(rows[it]) }.filter { it.installationId in installationIds }

  override suspend fun get(id: String): Session? = withPullRequest(rows[id])

  private fun withPullRequest(session: Session?): Session? =
      session?.copy(pullRequest = prs[session.id])
}
