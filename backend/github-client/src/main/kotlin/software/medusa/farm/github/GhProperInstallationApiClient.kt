package software.medusa.farm.github

import java.net.http.HttpClient
import java.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val httpOk = 200
private const val httpCreated = 201
private const val httpUnprocessable = 422

// Grey, so a label made by a client rather than a person does not claim a meaning by its colour.
private const val defaultLabelColor = "ededed"

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

  override suspend fun ensureLabel(repo: GhRepoFullName, name: String) {
    val response =
        http.post(
            "/repos/${repo.value}/labels",
            bearer = tokenProvider.provideToken(),
            body = gitHubJson.encodeToString(NewLabelDto(name = name, color = defaultLabelColor)),
        )
    // Already there is the outcome asked for, and GitHub reports it as unprocessable rather than
    // as success.
    check(response.statusCode() == httpCreated || response.statusCode() == httpUnprocessable) {
      "GitHub label creation failed: ${response.statusCode()} ${response.body()}"
    }
  }

  override suspend fun createIssue(
      repo: GhRepoFullName,
      title: String,
      body: String,
      labels: List<String>,
  ): GhIssue {
    val response =
        http.post(
            "/repos/${repo.value}/issues",
            bearer = tokenProvider.provideToken(),
            body =
                gitHubJson.encodeToString(NewIssueDto(title = title, body = body, labels = labels)),
        )
    check(response.statusCode() == httpCreated) {
      "GitHub issue creation failed: ${response.statusCode()} ${response.body()}"
    }

    val opened = gitHubJson.decodeFromString<OpenedIssueDto>(response.body())

    return GhIssue(number = opened.number, title = opened.title, labels = labels)
  }

  override suspend fun listOpenPullRequests(repo: GhRepoFullName): List<GhPullRequest> =
      http
          .getPaged(
              "/repos/${repo.value}/pulls?state=open&sort=created&direction=desc",
              tokenProvider,
          ) { body ->
            gitHubJson.decodeFromString<List<PullRequestDto>>(body).map { it.toGhPullRequest() }
          }
          .toList()

  override suspend fun listPullRequestPaths(repo: GhRepoFullName, number: Int): List<String> =
      http
          .getPaged("/repos/${repo.value}/pulls/$number/files", tokenProvider) { body ->
            gitHubJson.decodeFromString<List<PullRequestFileDto>>(body).map { it.filename }
          }
          .toList()

  override suspend fun createReview(
      repo: GhRepoFullName,
      number: Int,
      verdict: GhReviewVerdict,
      body: String,
      comments: List<GhNewReviewComment>,
  ) {
    val response =
        http.post(
            "/repos/${repo.value}/pulls/$number/reviews",
            bearer = tokenProvider.provideToken(),
            body =
                gitHubJson.encodeToString(
                    NewReviewDto(
                        event = verdict.name,
                        body = body,
                        comments =
                            comments.map { NewReviewCommentDto(path = it.path, body = it.body) },
                    )
                ),
        )
    check(response.statusCode() == httpOk || response.statusCode() == httpCreated) {
      "GitHub review submission failed: ${response.statusCode()} ${response.body()}"
    }
  }

  override suspend fun mergePullRequest(
      repo: GhRepoFullName,
      number: Int,
      method: GhMergeMethod,
  ) {
    val response =
        http.put(
            "/repos/${repo.value}/pulls/$number/merge",
            bearer = tokenProvider.provideToken(),
            body = gitHubJson.encodeToString(MergeDto(mergeMethod = method.wireValue)),
        )
    check(response.statusCode() == httpOk) {
      "GitHub pull request merge failed: ${response.statusCode()} ${response.body()}"
    }
  }

  override suspend fun listReviews(
      repo: GhRepoFullName,
      number: Int,
  ): List<GhPullRequestReview> =
      http
          .getPaged("/repos/${repo.value}/pulls/$number/reviews", tokenProvider) { body ->
            gitHubJson.decodeFromString<List<ReviewDto>>(body).map { it.toGhPullRequestReview() }
          }
          .toList()

  override suspend fun listReviewComments(
      repo: GhRepoFullName,
      number: Int,
  ): List<GhPullRequestReviewComment> =
      http
          .getPaged("/repos/${repo.value}/pulls/$number/comments", tokenProvider) { body ->
            gitHubJson.decodeFromString<List<ReviewCommentDto>>(body).map {
              it.toGhPullRequestReviewComment()
            }
          }
          .toList()

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
    @SerialName("merged_at") val mergedAt: String? = null,
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
        mergedAt = mergedAt?.let(Instant::parse),
    )

@Serializable private class NewLabelDto(val name: String, val color: String)

@Serializable
private class NewIssueDto(val title: String, val body: String, val labels: List<String>)

@Serializable private class OpenedIssueDto(val number: Int, val title: String)

@Serializable
private class NewReviewDto(
    val event: String,
    val body: String,
    val comments: List<NewReviewCommentDto>,
)

// subject_type says the comment is against the file rather than a line of it, which is what lets
// one be left without knowing the diff.
@Serializable
private class NewReviewCommentDto(
    val path: String,
    val body: String,
    @SerialName("subject_type") val subjectType: String = "file",
)

@Serializable private class MergeDto(@SerialName("merge_method") val mergeMethod: String)

@Serializable private class PullRequestFileDto(val filename: String)

@Serializable
private class ReviewDto(
    val id: Long,
    val state: String,
    val body: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
)

@Serializable
private class ReviewCommentDto(
    @SerialName("pull_request_review_id") val reviewId: Long,
    val path: String,
    val line: Int? = null,
    val body: String,
)

private fun ReviewDto.toGhPullRequestReview(): GhPullRequestReview =
    GhPullRequestReview(
        id = id,
        state =
            when (state) {
              "APPROVED" -> GhPullRequestReviewState.APPROVED
              "CHANGES_REQUESTED" -> GhPullRequestReviewState.CHANGES_REQUESTED
              "COMMENTED" -> GhPullRequestReviewState.COMMENTED
              "DISMISSED" -> GhPullRequestReviewState.DISMISSED
              else -> GhPullRequestReviewState.UNRECOGNIZED
            },
        body = body.orEmpty(),
        // A review still being drafted has not been submitted; it is not one anybody can act on.
        submittedAt = submittedAt?.let(Instant::parse) ?: Instant.EPOCH,
    )

private fun ReviewCommentDto.toGhPullRequestReviewComment(): GhPullRequestReviewComment =
    GhPullRequestReviewComment(reviewId = reviewId, path = path, line = line, body = body)

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
