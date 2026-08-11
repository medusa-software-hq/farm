package software.medusa.farm.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.shared.db.FarmDatabase

/** [LinkedOrgStore] backed by a Postgres database. */
class PostgresLinkedOrgStore(
    private val database: FarmDatabase,
) : LinkedOrgStore {
  override suspend fun link(installationId: Long, orgLogin: String) {
    withContext(Dispatchers.IO) { database.linkedOrgsQueries.link(installationId, orgLogin) }
  }

  override suspend fun list(): List<LinkedOrg> =
      withContext(Dispatchers.IO) {
        database.linkedOrgsQueries.list().executeAsList().map {
          LinkedOrg(it.installation_id, it.org_login)
        }
      }

  override suspend fun getByOrg(orgLogin: String): LinkedOrg? =
      withContext(Dispatchers.IO) {
        database.linkedOrgsQueries.getByOrg(orgLogin).executeAsOneOrNull()?.let {
          LinkedOrg(it.installation_id, it.org_login)
        }
      }
}
