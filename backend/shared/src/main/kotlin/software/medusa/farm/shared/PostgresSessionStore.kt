package software.medusa.farm.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.shared.db.FarmDatabase

/** [SessionStore] backed by a Postgres database. */
class PostgresSessionStore(
    private val database: FarmDatabase,
) : SessionStore {
  override suspend fun create(
      id: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
  ) {
    withContext(Dispatchers.IO) {
      database.sessionsQueries.create(
          id = id,
          installationId = installationId,
          githubRepoId = githubRepoId,
          number = number,
          state = SessionState.RUNNING.name,
      )
    }
  }

  override suspend fun complete(id: String) {
    withContext(Dispatchers.IO) {
      database.sessionsQueries.complete(state = SessionState.COMPLETED.name, id = id)
    }
  }

  override suspend fun listForOrgs(installationIds: List<Long>): List<Session> =
      withContext(Dispatchers.IO) {
        installationIds.flatMap { installationId ->
          database.sessionsQueries.listForOrg(installationId).executeAsList().map {
            Session(
                id = it.id,
                installationId = it.installation_id,
                githubRepoId = it.github_repo_id,
                number = it.number,
                state = SessionState.valueOf(it.state),
            )
          }
        }
      }
}
