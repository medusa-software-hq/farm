package software.medusa.farm.shared

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IssueStore]. The [clock] supplies the "seen now" instant that the soft-orphan
 * watermark is compared against — the real-time source in production, a controllable one under
 * test.
 */
class InMemoryIssueStore(private val clock: Clock) : IssueStore {
  private class Row(val issue: Issue, val lastSeenAt: Instant, val orphanedAt: Instant?)

  // Keyed by (githubRepoId, number).
  private val rows = ConcurrentHashMap<Pair<Long, Int>, Row>()
  private val reconcileLock = Any()

  override suspend fun reconcile(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      fetched: List<FetchedIssue>,
      syncStartedAt: Instant,
  ) {
    // One reconcile at a time so the upsert-then-orphan pair is atomic, mirroring the DB
    // transaction.
    synchronized(reconcileLock) {
      val now = clock.instant()
      for (fetchedIssue in fetched) {
        val key = githubRepoId to fetchedIssue.number
        rows[key] =
            Row(
                issue =
                    Issue(
                        githubRepoId = githubRepoId,
                        installationId = installationId,
                        repoFullName = repoFullName,
                        number = fetchedIssue.number,
                        title = fetchedIssue.title,
                    ),
                lastSeenAt = now,
                orphanedAt = null,
            )
      }
      for ((key, row) in rows) {
        if (key.first == githubRepoId && row.orphanedAt == null && row.lastSeenAt < syncStartedAt) {
          rows[key] = Row(row.issue, row.lastSeenAt, orphanedAt = now)
        }
      }
    }
  }

  override suspend fun listActiveForOrgs(installationIds: List<Long>): List<Issue> =
      rows.values
          .filter { it.issue.installationId in installationIds && it.orphanedAt == null }
          .map { it.issue }
          .sortedWith(compareBy({ it.repoFullName }, { it.number }))
}
