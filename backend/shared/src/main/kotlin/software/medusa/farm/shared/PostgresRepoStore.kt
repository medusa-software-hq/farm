package software.medusa.farm.shared

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.shared.db.FarmDatabase

/** [RepoStore] backed by a Postgres database. */
class PostgresRepoStore(
    private val database: FarmDatabase,
) : RepoStore {
  override suspend fun reconcile(
      installationId: Long,
      fetched: List<FetchedRepo>,
      syncStartedAt: Instant,
  ) {
    withContext(Dispatchers.IO) {
      database.reposQueries.transaction {
        for (repo in fetched) {
          database.reposQueries.upsert(
              githubRepoId = repo.githubRepoId,
              installationId = installationId,
              fullName = repo.fullName,
              name = repo.name,
              isPrivate = repo.isPrivate,
              defaultBranch = repo.defaultBranch,
          )
        }
        database.reposQueries.orphanMissing(installationId, syncStartedAt.atOffset(ZoneOffset.UTC))
      }
    }
  }

  override suspend fun listActive(installationId: Long): List<Repo> =
      withContext(Dispatchers.IO) {
        database.reposQueries.listActive(installationId).executeAsList().map {
          Repo(
              githubRepoId = it.github_repo_id,
              installationId = it.installation_id,
              fullName = it.full_name,
              name = it.name,
              isPrivate = it.isPrivate,
              defaultBranch = it.default_branch,
          )
        }
      }

  override suspend fun listActiveForOrgs(installationIds: List<Long>): List<Repo> =
      withContext(Dispatchers.IO) { installationIds.flatMap { listActive(it) } }
}
