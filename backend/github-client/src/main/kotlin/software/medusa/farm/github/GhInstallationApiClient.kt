package software.medusa.farm.github

/** The resource surface plus the endpoints reachable only with an installation token. */
interface GhInstallationApiClient : GhResourcesApiClient {
  suspend fun listInstallationRepositories(): List<GhRepo>
}
