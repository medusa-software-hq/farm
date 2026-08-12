package software.medusa.farm.shared

/** The processing sessions Farm opens against issues. */
interface SessionStore {
  /**
   * Opens a running session with the given id. Idempotent on the id (safe under activity retry).
   */
  suspend fun create(id: String, installationId: Long, githubRepoId: Long, number: Int)

  /** Marks the session completed. */
  suspend fun complete(id: String)

  suspend fun listForOrgs(installationIds: List<Long>): List<Session>
}
