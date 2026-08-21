package software.medusa.farm.github

import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

private const val httpOk = 200
private const val httpCreated = 201
private const val httpNoContent = 204
private const val httpNotFound = 404
private const val httpUnprocessable = 422

// Grey, so a label made by a client rather than a person does not claim a meaning by its colour.
private const val defaultLabelColor = "ededed"

/**
 * [value] as one segment of a URL path. Label names are free text — a colon, a slash or a space is
 * allowed in one — so a name cannot be pasted into a path as it stands.
 */
private fun pathSegment(value: String): String =
    // The JDK encodes for a form, where a space is "+"; in a path it is "%20".
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

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
  private val orgOpenIssues = GhOrgOpenIssues(GhGraphQl(baseUrl, httpClient), tokenProvider)

  override suspend fun listInstallationRepositories(): List<GhRepo> =
      http
          .getPaged("/installation/repositories", tokenProvider) { body ->
            gitHubJson.decodeFromString<RepositoriesPageDto>(body).repositories.map {
              it.toGhRepo()
            }
          }
          .toList()

  override suspend fun listReposWithOpenIssues(): List<GhRepoWithOpenIssues> {
    val reachable = listInstallationRepositories()
    // An installation belongs to one account, so any repository it reaches names that account, and
    // one that reaches none has nothing to ask an org about.
    val org = reachable.firstOrNull()?.fullName?.owner ?: return emptyList()
    val issuesByRepo = orgOpenIssues.byRepo(org)

    // Each repository's fields come from the listing that decided it was reachable, so what a sync
    // stores about a repository is exactly what it stored before the org query existed.
    return reachable.mapNotNull { repo ->
      issuesByRepo[repo.id]?.let { GhRepoWithOpenIssues(repo, it) }
    }
  }

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

  override suspend fun createRepositoryFromTemplate(
      template: GhRepoFullName,
      owner: String,
      name: String,
      isPrivate: Boolean,
  ): GhRepo {
    val response =
        http.post(
            "/repos/${template.value}/generate",
            bearer = tokenProvider.provideToken(),
            body =
                gitHubJson.encodeToString(
                    GenerateRepoDto(owner = owner, name = name, private = isPrivate)
                ),
        )
    check(response.statusCode() == httpCreated) {
      "GitHub repository creation failed: ${response.statusCode()} ${response.body()}"
    }

    return gitHubJson.decodeFromString<RepositoryDto>(response.body()).toGhRepo()
  }

  override suspend fun deleteRepository(repo: GhRepoFullName) {
    val response = http.delete("/repos/${repo.value}", bearer = tokenProvider.provideToken())
    check(response.statusCode() == httpNoContent) {
      "GitHub repository deletion failed: ${response.statusCode()} ${response.body()}"
    }
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

  override suspend fun removeLabel(repo: GhRepoFullName, number: Int, name: String) {
    val response =
        http.delete(
            "/repos/${repo.value}/issues/$number/labels/${pathSegment(name)}",
            bearer = tokenProvider.provideToken(),
        )
    // Not carrying it is the outcome asked for, and GitHub reports that as not found rather than
    // as success.
    check(response.statusCode() == httpOk || response.statusCode() == httpNotFound) {
      "GitHub label removal failed: ${response.statusCode()} ${response.body()}"
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

  override suspend fun listPullRequestFiles(
      repo: GhRepoFullName,
      number: Int,
  ): List<GhPullRequestFile> =
      http
          .getPaged("/repos/${repo.value}/pulls/$number/files", tokenProvider) { body ->
            gitHubJson.decodeFromString<List<PullRequestFileDto>>(body).map {
              GhPullRequestFile(path = it.filename, patch = it.patch)
            }
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
                            comments.map {
                              NewReviewCommentDto(path = it.path, line = it.line, body = it.body)
                            },
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

  override suspend fun listCheckRuns(repo: GhRepoFullName, ref: String): List<GhCheckRun> =
      http
          .getPaged("/repos/${repo.value}/commits/$ref/check-runs", tokenProvider) { body ->
            gitHubJson.decodeFromString<CheckRunsPageDto>(body).checkRuns.map { it.toGhCheckRun() }
          }
          .toList()

  override suspend fun listRequiredCheckNames(
      repo: GhRepoFullName,
      branch: String,
  ): List<String> =
      try {
        http
            .getPaged(
                "/repos/${repo.value}/rules/branches/${pathSegment(branch)}",
                tokenProvider,
            ) { body ->
              gitHubJson.decodeFromString<List<BranchRuleDto>>(body).flatMap {
                it.requiredCheckNames()
              }
            }
            .toList()
      } catch (refusal: GhRequestFailed) {
        // A repository whose plan does not include rules, or a token not allowed to read them,
        // answers by refusing. Neither is an answer that changes by asking again, and a branch
        // whose requirements cannot be read requires nothing that anyone can be held to — the same
        // as the older per-branch protection, which reads as empty here too.
        //
        // Only a refusal. A rate limit or a fault means the requirements are unknown rather than
        // absent, and a pull request must not be treated as unguarded because GitHub was busy.
        if (!refusal.refused) throw refusal
        emptyList()
      }

  override suspend fun listCheckRunAnnotations(
      repo: GhRepoFullName,
      checkRunId: GhCheckRunId,
  ): List<GhCheckAnnotation> =
      http
          .getPaged(
              "/repos/${repo.value}/check-runs/${checkRunId.value}/annotations",
              tokenProvider,
          ) { body ->
            gitHubJson.decodeFromString<List<AnnotationDto>>(body).map { it.toGhCheckAnnotation() }
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
    val base: PullRequestBaseDto,
)

@Serializable private class PullRequestHeadDto(val sha: String)

@Serializable private class PullRequestBaseDto(val ref: String)

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
        baseBranch = base.ref,
        mergedAt = mergedAt?.let(Instant::parse),
    )

@Serializable
private class GenerateRepoDto(val owner: String, val name: String, val private: Boolean)

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

// No side: GitHub reads the line as the head revision's, which is the side a pull request adds on.
@Serializable
private class NewReviewCommentDto(
    val path: String,
    val line: Int,
    val body: String,
)

@Serializable private class MergeDto(@SerialName("merge_method") val mergeMethod: String)

@Serializable private class PullRequestFileDto(val filename: String, val patch: String? = null)

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

@Serializable
private class CheckRunsPageDto(@SerialName("check_runs") val checkRuns: List<CheckRunDto>)

@Serializable
private class CheckRunDto(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val output: CheckRunOutputDto? = null,
)

@Serializable
private class CheckRunOutputDto(
    val title: String? = null,
    val summary: String? = null,
    val text: String? = null,
)

private fun CheckRunDto.toGhCheckRun(): GhCheckRun =
    GhCheckRun(
        id = GhCheckRunId(id),
        name = name,
        status =
            when (status) {
              "queued" -> GhCheckRunStatus.QUEUED
              "in_progress" -> GhCheckRunStatus.IN_PROGRESS
              "completed" -> GhCheckRunStatus.COMPLETED
              else -> GhCheckRunStatus.UNRECOGNIZED
            },
        conclusion =
            conclusion?.let {
              when (it) {
                "success" -> GhCheckRunConclusion.SUCCESS
                "failure" -> GhCheckRunConclusion.FAILURE
                "neutral" -> GhCheckRunConclusion.NEUTRAL
                "cancelled" -> GhCheckRunConclusion.CANCELLED
                "timed_out" -> GhCheckRunConclusion.TIMED_OUT
                "action_required" -> GhCheckRunConclusion.ACTION_REQUIRED
                "skipped" -> GhCheckRunConclusion.SKIPPED
                "stale" -> GhCheckRunConclusion.STALE
                "startup_failure" -> GhCheckRunConclusion.STARTUP_FAILURE
                else -> GhCheckRunConclusion.UNRECOGNIZED
              }
            },
        output =
            GhCheckRunOutput(
                title = output?.title.orEmpty(),
                summary = output?.summary.orEmpty(),
                text = output?.text.orEmpty(),
            ),
    )

// Rules of every kind come back in one list, each with settings of a shape only its own kind
// knows, so a rule's parameters are left undecoded until its kind says what they are.
@Serializable private class BranchRuleDto(val type: String, val parameters: JsonElement? = null)

@Serializable
private class RequiredStatusChecksParametersDto(
    @SerialName("required_status_checks") val requiredStatusChecks: List<RequiredStatusCheckDto>
)

@Serializable private class RequiredStatusCheckDto(val context: String)

private fun BranchRuleDto.requiredCheckNames(): List<String> {
  if (type != "required_status_checks") return emptyList()
  val parameters = parameters ?: return emptyList()
  return gitHubJson
      .decodeFromJsonElement<RequiredStatusChecksParametersDto>(parameters)
      .requiredStatusChecks
      .map { it.context }
}

@Serializable
private class AnnotationDto(
    val path: String,
    @SerialName("start_line") val startLine: Int? = null,
    val message: String? = null,
)

private fun AnnotationDto.toGhCheckAnnotation(): GhCheckAnnotation =
    GhCheckAnnotation(
        path = path,
        // A check that has nothing to point at in a file says so with line zero rather than by
        // leaving the file out.
        startLine = startLine?.takeIf { it > 0 },
        message = message.orEmpty(),
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
