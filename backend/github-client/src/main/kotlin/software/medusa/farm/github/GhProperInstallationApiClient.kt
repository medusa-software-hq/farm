package software.medusa.farm.github

import java.net.http.HttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val httpCreated = 201

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

  override suspend fun listInstallationRepositories(): List<GhRepo> =
      http
          .getPaged("/installation/repositories", tokenProvider) { body ->
            gitHubJson.decodeFromString<RepositoriesPageDto>(body).repositories.map {
              it.toGhRepo()
            }
          }
          .toList()

  override suspend fun createIssueComment(repo: GhRepoFullName, number: Int, body: String) {
    val response =
        http.post(
            "/repos/${repo.value}/issues/$number/comments",
            bearer = tokenProvider.provideToken(),
            body = gitHubJson.encodeToString(CommentDto(body)),
        )
    check(response.statusCode() == httpCreated) {
      "GitHub issue comment failed: ${response.statusCode()} ${response.body()}"
    }
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

@Serializable private class CommentDto(val body: String)

@Serializable private class RepositoriesPageDto(val repositories: List<RepositoryDto>)

@Serializable
private class RepositoryDto(
    val id: Long,
    @SerialName("full_name") val fullName: String,
    val name: String,
    val private: Boolean,
    @SerialName("default_branch") val defaultBranch: String,
)

private fun RepositoryDto.toGhRepo(): GhRepo =
    GhRepo(
        id = GhRepoId(id),
        fullName = GhRepoFullName(fullName),
        name = name,
        isPrivate = private,
        defaultBranch = defaultBranch,
    )
