package software.medusa.farm.github

import java.net.http.HttpClient

/** Builds a fresh installation client per installation, each over its own refreshing token. */
class GhProperInstallationApiClientProvider(
    private val appApiClient: GhAppApiClient,
    private val baseUrl: String = gitHubApiBaseUrl,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : GhInstallationApiClientProvider {
  override fun provideForInstallation(installationId: GhInstallationId): GhInstallationApiClient =
      GhProperInstallationApiClient.build(
          GhRefreshingInstallationTokenProvider(appApiClient, installationId),
          baseUrl,
          httpClient,
      )
}
