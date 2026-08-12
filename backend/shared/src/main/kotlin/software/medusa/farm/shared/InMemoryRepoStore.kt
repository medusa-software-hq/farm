package software.medusa.farm.shared

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [RepoStore]. The [clock] supplies the "seen now" instant that the soft-orphan watermark
 * is compared against — the real-time source in production, a controllable one under test.
 */
class InMemoryRepoStore(private val clock: Clock) : RepoStore {
  private class Row(val repo: Repo, val lastSeenAt: Instant, val orphanedAt: Instant?)

  // Keyed by (installationId, githubRepoId).
  private val rows = ConcurrentHashMap<Pair<Long, Long>, Row>()
  private val reconcileLock = Any()

  override suspend fun reconcile(
      installationId: Long,
      fetched: List<FetchedRepo>,
      syncStartedAt: Instant,
  ) {
    // One reconcile at a time so the upsert-then-orphan pair is atomic, mirroring the DB
    // transaction.
    synchronized(reconcileLock) {
      val now = clock.instant()
      for (fetchedRepo in fetched) {
        val key = installationId to fetchedRepo.githubRepoId
        rows[key] =
            Row(
                repo =
                    Repo(
                        githubRepoId = fetchedRepo.githubRepoId,
                        installationId = installationId,
                        fullName = fetchedRepo.fullName,
                        name = fetchedRepo.name,
                        isPrivate = fetchedRepo.isPrivate,
                        defaultBranch = fetchedRepo.defaultBranch,
                    ),
                lastSeenAt = now,
                orphanedAt = null,
            )
      }
      for ((key, row) in rows) {
        if (
            key.first == installationId && row.orphanedAt == null && row.lastSeenAt < syncStartedAt
        ) {
          rows[key] = Row(row.repo, row.lastSeenAt, orphanedAt = now)
        }
      }
    }
  }

  override suspend fun listActive(installationId: Long): List<Repo> =
      rows.values
          .filter { it.repo.installationId == installationId && it.orphanedAt == null }
          .map { it.repo }
          .sortedBy { it.fullName }

  override suspend fun listActiveForOrgs(installationIds: List<Long>): List<Repo> =
      installationIds.flatMap {
        listActive(it)
      }
}
