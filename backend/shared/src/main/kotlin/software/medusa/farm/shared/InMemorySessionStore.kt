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

  override suspend fun startRun(id: String, ordinal: Int) {
    runs.getOrPut(id) { ConcurrentHashMap() }[ordinal] =
        SessionRun.Running(
            ordinal = ordinal,
            log = AgentRunLog(entries = emptyList()),
            createdAt = clock.instant(),
        )
  }

  override suspend fun appendRunEntry(
      id: String,
      ordinal: Int,
      position: Int,
      entry: AgentRunEntry,
  ) {
    val run = runs[id]?.get(ordinal) ?: error("run $ordinal of session $id was never started")
    val entries = run.log.entries.toMutableList()
    // Addressed by position rather than appended blindly, so writing the same one twice — which a
    // retried write is entitled to do — leaves the log as it was.
    if (position < entries.size) entries[position] = entry else entries.add(entry)
    runs.getValue(id)[ordinal] =
        SessionRun.Running(
            ordinal = ordinal,
            log = AgentRunLog(entries = entries),
            createdAt = run.createdAt,
        )
  }

  override suspend fun finishRun(
      id: String,
      ordinal: Int,
      outcome: AgentRunOutcome,
      cost: AgentRunCost?,
      summary: String,
  ) {
    val run = runs[id]?.get(ordinal) ?: error("run $ordinal of session $id was never started")
    runs.getValue(id)[ordinal] =
        SessionRun.Finished(
            ordinal = ordinal,
            log = run.log,
            outcome = outcome,
            cost = cost,
            summary = summary,
            createdAt = run.createdAt,
        )
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
