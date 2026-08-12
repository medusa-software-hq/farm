package software.medusa.farm.shared

import java.util.concurrent.ConcurrentHashMap

/** In-memory [SessionStore]; ordering by insertion suffices for the single-flow local stack. */
class InMemorySessionStore : SessionStore {
  // A linked map so listForOrgs preserves creation order (the "latest wins" the API relies on).
  private val rows = ConcurrentHashMap<String, Session>()
  private val order = java.util.Collections.synchronizedList(mutableListOf<String>())

  override suspend fun create(
      id: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
  ) {
    // Idempotent on the id, mirroring the DB's ON CONFLICT DO NOTHING.
    if (
        rows.putIfAbsent(
            id,
            Session(id, installationId, githubRepoId, number, SessionState.RUNNING),
        ) == null
    ) {
      order += id
    }
  }

  override suspend fun complete(id: String) {
    rows.computeIfPresent(id) { _, s ->
      Session(s.id, s.installationId, s.githubRepoId, s.number, SessionState.COMPLETED)
    }
  }

  override suspend fun listForOrgs(installationIds: List<Long>): List<Session> =
      order.mapNotNull { rows[it] }.filter { it.installationId in installationIds }
}
