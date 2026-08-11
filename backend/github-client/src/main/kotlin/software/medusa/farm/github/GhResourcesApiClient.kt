package software.medusa.farm.github

/**
 * The credential-agnostic resource surface: endpoints any bearer token can drive, whether it is an
 * installation token or a user's PAT. Only add endpoints here that both kinds of credential can
 * reach — installation- or app-scoped ones belong on their own surface.
 */
interface GhResourcesApiClient {
  suspend fun listIssues(repo: GhRepoFullName): List<GhIssue>
}
