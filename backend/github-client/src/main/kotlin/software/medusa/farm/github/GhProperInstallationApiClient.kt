package software.medusa.farm.github

import java.net.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val httpOk = 200
private const val reposPageSize = 100

/**
 * The installation surface for one org: its installation-only endpoints, with the resource surface
 * delegated to a universal client over the same token so both speak through one credential.
 */
class GhProperInstallationApiClient
private constructor(
    private val tokenProvider: GhTokenProvider,
    baseUrl: String,
    httpClient: HttpClient,
) :
    GhInstallationApiClient,
    GhResourcesApiClient by GhUniversalResourcesApiClient(tokenProvider, baseUrl, httpClient) {
  private val http = GhHttp(baseUrl, httpClient)

  override suspend fun listInstallationRepositories(): List<GhRepoFullName> {
    val fullNames = mutableListOf<GhRepoFullName>()
    var page = 1
    while (true) {
      val response =
          http.get(
              "/installation/repositories?per_page=$reposPageSize&page=$page",
              bearer = tokenProvider.provideToken(),
          )
      check(response.statusCode() == httpOk) {
        "GitHub repository listing failed: ${response.statusCode()} ${response.body()}"
      }
      val decoded = gitHubJson.decodeFromString<RepositoriesPageDto>(response.body())
      fullNames += decoded.repositories.map { GhRepoFullName(it.fullName) }
      if (decoded.repositories.isEmpty() || fullNames.size >= decoded.totalCount) break
      page++
    }
    return fullNames
  }

  companion object {
    fun build(
        tokenProvider: GhTokenProvider,
        baseUrl: String = gitHubApiBaseUrl,
        httpClient: HttpClient = HttpClient.newHttpClient(),
    ): GhProperInstallationApiClient =
        GhProperInstallationApiClient(tokenProvider, baseUrl, httpClient)
  }
}

@Serializable
private class RepositoriesPageDto(
    @SerialName("total_count") val totalCount: Int,
    val repositories: List<RepositoryDto>,
)

@Serializable private class RepositoryDto(@SerialName("full_name") val fullName: String)
