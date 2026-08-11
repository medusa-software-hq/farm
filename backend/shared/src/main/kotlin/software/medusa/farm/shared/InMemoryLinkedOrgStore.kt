package software.medusa.farm.shared

import java.util.concurrent.ConcurrentHashMap

/** In-memory [LinkedOrgStore]. */
class InMemoryLinkedOrgStore : LinkedOrgStore {
  private val orgLoginByInstallationId = ConcurrentHashMap<Long, String>()

  override suspend fun link(installationId: Long, orgLogin: String) {
    orgLoginByInstallationId[installationId] = orgLogin
  }

  override suspend fun list(): List<LinkedOrg> =
      orgLoginByInstallationId.entries.map { LinkedOrg(it.key, it.value) }.sortedBy { it.orgLogin }

  override suspend fun getByOrg(orgLogin: String): LinkedOrg? =
      orgLoginByInstallationId.entries
          .firstOrNull { it.value == orgLogin }
          ?.let { LinkedOrg(it.key, it.value) }
}
