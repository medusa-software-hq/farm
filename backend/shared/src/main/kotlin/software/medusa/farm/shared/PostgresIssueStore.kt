package software.medusa.farm.shared

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.shared.db.FarmDatabase

/** [IssueStore] backed by a Postgres database. */
class PostgresIssueStore(
    private val database: FarmDatabase,
) : IssueStore {
  override suspend fun reconcile(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      fetched: List<FetchedIssue>,
      syncStartedAt: Instant,
  ) {
    withContext(Dispatchers.IO) {
      database.issuesQueries.transaction {
        for (issue in fetched) {
          database.issuesQueries.upsert(
              githubRepoId = githubRepoId,
              number = issue.number,
              installationId = installationId,
              repoFullName = repoFullName,
              title = issue.title,
          )
        }
        database.issuesQueries.orphanMissing(githubRepoId, syncStartedAt.atOffset(ZoneOffset.UTC))
      }
    }
  }

  override suspend fun listActiveForOrgs(installationIds: List<Long>): List<Issue> =
      withContext(Dispatchers.IO) {
        installationIds.flatMap { installationId ->
          database.issuesQueries.listActiveForOrg(installationId).executeAsList().map {
            Issue(
                githubRepoId = it.github_repo_id,
                installationId = it.installation_id,
                repoFullName = it.repo_full_name,
                number = it.number,
                title = it.title,
            )
          }
        }
      }
}
