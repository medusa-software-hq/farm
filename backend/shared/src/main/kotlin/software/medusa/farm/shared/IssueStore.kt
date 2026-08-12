package software.medusa.farm.shared

import java.time.Instant

/**
 * The open issues on the Farm app's repos, kept as a domain entity: soft-orphaned, never deleted.
 */
interface IssueStore {
  /**
   * Reconciles one repo's issues against [fetched] in a single transaction: upserts each fetched
   * issue as seen now (reactivating any that were orphaned), then orphans every still-active row
   * for this repo not touched since [syncStartedAt]. Idempotent; reconciling the same set twice
   * orphans nothing. An empty [fetched] is a valid "no open issues" and orphans any that were open.
   */
  suspend fun reconcile(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      fetched: List<FetchedIssue>,
      syncStartedAt: Instant,
  )

  suspend fun listActiveForOrgs(installationIds: List<Long>): List<Issue>
}
