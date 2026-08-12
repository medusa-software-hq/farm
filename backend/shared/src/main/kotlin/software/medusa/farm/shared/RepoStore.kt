package software.medusa.farm.shared

import java.time.Instant

/** The repos the Farm app can reach, kept as a domain entity: soft-orphaned, never deleted. */
interface RepoStore {
  /**
   * Reconciles one installation's repos against [fetched] in a single transaction: upserts each
   * fetched repo as seen now (reactivating any that were orphaned), then orphans every still-active
   * row not touched since [syncStartedAt]. Idempotent; reconciling the same set twice orphans
   * nothing.
   */
  suspend fun reconcile(installationId: Long, fetched: List<FetchedRepo>, syncStartedAt: Instant)

  suspend fun listActive(installationId: Long): List<Repo>

  suspend fun listActiveForOrgs(installationIds: List<Long>): List<Repo>
}
