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
  private val runs = ConcurrentHashMap<String, MutableMap<Int, SessionRun>>()
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

  override suspend fun recordRun(
      id: String,
      ordinal: Int,
      log: AgentRunLog,
      outcome: AgentRunOutcome,
      cost: AgentRunCost?,
  ) {
    val run =
        SessionRun(
            ordinal = ordinal,
            log = log,
            outcome = outcome,
            cost = cost,
            createdAt = clock.instant(),
        )
    runs.getOrPut(id) { ConcurrentHashMap() }[ordinal] = run
  }

  override suspend fun getRuns(id: String): List<SessionRun> =
      runs[id]?.values.orEmpty().sortedBy { it.ordinal }

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
