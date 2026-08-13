package software.medusa.farm.shared

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [SessionStore]. The [clock] supplies session timestamps; insertion order is preserved
 * so the "latest session per issue" the issues read relies on stays correct.
 */
class InMemorySessionStore(private val clock: Clock) : SessionStore {
  private val rows = ConcurrentHashMap<String, Session>()
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

  private fun transition(id: String, state: SessionState) {
    val now = clock.instant()
    rows.computeIfPresent(id) { _, s ->
      Session(
          id = s.id,
          installationId = s.installationId,
          githubRepoId = s.githubRepoId,
          number = s.number,
          repoFullName = s.repoFullName,
          title = s.title,
          state = state,
          startedAt = s.startedAt,
          finishedAt = now,
      )
    }
  }

  override suspend fun listForOrgs(installationIds: List<Long>): List<Session> =
      order.mapNotNull { rows[it] }.filter { it.installationId in installationIds }

  override suspend fun get(id: String): Session? = rows[id]
}
