package software.medusa.farm.github

/** The resource surface plus the endpoints reachable only with an installation token. */
interface GhInstallationApiClient : GhResourcesApiClient {
  suspend fun listInstallationRepositories(): List<GhRepo>

  /** Posts a comment on an issue. Requires the App's Issues:write permission. */
  suspend fun createIssueComment(repo: GhRepoFullName, number: Int, body: String)
}
