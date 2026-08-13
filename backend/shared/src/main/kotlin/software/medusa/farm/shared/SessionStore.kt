package software.medusa.farm.shared

/** The processing sessions Farm opens against issues. */
interface SessionStore {
  /**
   * Opens a running session with the given id, recording the issue it works. Idempotent on the id
   * (safe under activity retry).
   */
  suspend fun create(
      id: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
      repoFullName: String,
      title: String,
  )

  /** Marks the session completed. */
  suspend fun complete(id: String)

  /** Marks the session failed. */
  suspend fun fail(id: String)

  suspend fun listForOrgs(installationIds: List<Long>): List<Session>

  suspend fun get(id: String): Session?
}
