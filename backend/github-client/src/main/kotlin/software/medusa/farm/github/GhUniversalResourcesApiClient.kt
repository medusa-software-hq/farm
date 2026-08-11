package software.medusa.farm.github

import java.net.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

private const val httpOk = 200
private const val issuesPageSize = 5

/**
 * The resource surface over whatever bearer token [tokenProvider] yields. Works the same whether
 * that token is an installation token or a PAT; that is the point of the seam.
 */
class GhUniversalResourcesApiClient(
    private val tokenProvider: GhTokenProvider,
    baseUrl: String = gitHubApiBaseUrl,
    httpClient: HttpClient = HttpClient.newHttpClient(),
) : GhResourcesApiClient {
  private val http = GhHttp(baseUrl, httpClient)

  // Bounded to a handful of recent issues; the demo call is per-repo, so this is deliberately
  // small.
  override suspend fun listIssues(repo: GhRepoFullName): List<GhIssue> {
    val response =
        http.get(
            "/repos/${repo.value}/issues?per_page=$issuesPageSize&state=open&sort=created",
            bearer = tokenProvider.provideToken(),
        )
    check(response.statusCode() == httpOk) {
      "GitHub issue listing failed: ${response.statusCode()} ${response.body()}"
    }
    return gitHubJson
        .decodeFromString<List<IssueDto>>(response.body())
        // GitHub's issues endpoint also returns pull requests; a `pull_request` field marks them.
        .filter { it.pullRequest == null }
        .map { GhIssue(number = it.number, title = it.title) }
  }
}

@Serializable
private class IssueDto(
    val number: Int,
    val title: String,
    @SerialName("pull_request") val pullRequest: JsonElement? = null,
)
