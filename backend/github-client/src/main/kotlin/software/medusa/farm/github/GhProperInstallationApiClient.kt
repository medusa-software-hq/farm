package software.medusa.farm.github

import java.net.http.HttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val httpOk = 200
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

  override suspend fun createPullRequest(
      repo: GhRepoFullName,
      head: String,
      base: String,
      title: String,
      body: String,
  ): GhPullRequest {
    val response =
        http.post(
            "/repos/${repo.value}/pulls",
            bearer = tokenProvider.provideToken(),
            body =
                gitHubJson.encodeToString(
                    CreatePullRequestDto(title = title, head = head, base = base, body = body)
                ),
        )
    check(response.statusCode() == httpCreated) {
      "GitHub pull request creation failed: ${response.statusCode()} ${response.body()}"
    }
    return gitHubJson.decodeFromString<PullRequestDto>(response.body()).toGhPullRequest()
  }

  override suspend fun getPullRequest(repo: GhRepoFullName, number: Int): GhPullRequest {
    val response =
        http.get("/repos/${repo.value}/pulls/$number", bearer = tokenProvider.provideToken())
    check(response.statusCode() == httpOk) {
      "GitHub pull request fetch failed: ${response.statusCode()} ${response.body()}"
    }
    return gitHubJson.decodeFromString<PullRequestDto>(response.body()).toGhPullRequest()
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

@Serializable
private class CreatePullRequestDto(
    val title: String,
    val head: String,
    val base: String,
    val body: String,
)

@Serializable
private class PullRequestDto(
    val number: Int,
    @SerialName("html_url") val htmlUrl: String,
    val state: String,
    val merged: Boolean = false,
    val head: PullRequestHeadDto,
)

@Serializable private class PullRequestHeadDto(val sha: String)

private fun PullRequestDto.toGhPullRequest(): GhPullRequest =
    GhPullRequest(
        number = number,
        url = htmlUrl,
        state =
            when {
              merged -> GhPullRequestState.MERGED
              state == "open" -> GhPullRequestState.OPEN
              else -> GhPullRequestState.CLOSED
            },
        headSha = head.sha,
    )

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
