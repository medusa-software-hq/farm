package software.medusa.farm.github

import java.net.http.HttpClient

/** Builds a fresh installation client per org, each over its own refreshing token. */
class GhProperInstallationApiClientProvider(
    private val appApiClient: GhAppApiClient,
    private val baseUrl: String = gitHubApiBaseUrl,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : GhInstallationApiClientProvider {
  override fun provideForOrg(orgLogin: GhOrgLogin): GhInstallationApiClient =
      GhProperInstallationApiClient.build(
          GhRefreshingInstallationTokenProvider(appApiClient, orgLogin),
          baseUrl,
          httpClient,
      )
}
