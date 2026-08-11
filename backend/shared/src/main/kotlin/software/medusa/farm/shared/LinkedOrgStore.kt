package software.medusa.farm.shared

/** The GitHub orgs linked to the Farm app, keyed by installation id. */
interface LinkedOrgStore {
  /** Records (or updates) the link between an installation id and its org. */
  suspend fun link(installationId: Long, orgLogin: String)

  suspend fun list(): List<LinkedOrg>

  suspend fun getByOrg(orgLogin: String): LinkedOrg?
}
