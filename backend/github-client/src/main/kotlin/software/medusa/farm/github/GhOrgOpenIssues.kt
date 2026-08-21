package software.medusa.farm.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

// GitHub refuses a query whose connections could yield more than 500,000 nodes, and the three
// nested here multiply: repositories, times each one's issues, times each issue's labels. These
// leave room to spare, and each connection is a page rather than a limit — what does not fit is
// followed by cursor rather than lost.
private const val reposPerPage = 50
private const val issuesPerPage = 100

// The one question asked of an issue's labels is whether it carries the label that opts it in, so
// this only has to exceed the number of labels anyone puts on one issue.
private const val labelsPerIssue = 50

// The whole org in one round trip: every repository, each with a page of its open issues. GraphQL's
// `issues` is issues alone, so unlike the REST listing there are no pull requests to filter out.
private val orgReposDocument =
    """
    query(${'$'}login: String!, ${'$'}after: String) {
      organization(login: ${'$'}login) {
        repositories(first: $reposPerPage, after: ${'$'}after) {
          pageInfo { hasNextPage endCursor }
          nodes {
            databaseId
            name
            issues(first: $issuesPerPage, states: OPEN) {
              pageInfo { hasNextPage endCursor }
              nodes { number title labels(first: $labelsPerIssue) { nodes { name } } }
            }
          }
        }
      }
    }
    """
        .trimIndent()

private val repoIssuesDocument =
    """
    query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
      repository(owner: ${'$'}owner, name: ${'$'}name) {
        issues(first: $issuesPerPage, states: OPEN, after: ${'$'}after) {
          pageInfo { hasNextPage endCursor }
          nodes { number title labels(first: $labelsPerIssue) { nodes { name } } }
        }
      }
    }
    """
        .trimIndent()

/**
 * The open issues of every repository in one org, keyed by the repository's numeric id — the whole
 * sweep's reading of GitHub, asked as one query rather than one per repository.
 */
internal class GhOrgOpenIssues(
    private val graphQl: GhGraphQl,
    private val tokenProvider: GhTokenProvider,
) {
  suspend fun byRepo(org: GhOrgLogin): Map<GhRepoId, List<GhIssue>> {
    val issuesByRepo = mutableMapOf<GhRepoId, List<GhIssue>>()
    var after: String? = null
    do {
      val page = repositoriesPage(org, after)
      for (node in page.nodes) {
        issuesByRepo[GhRepoId(node.databaseId)] = allIssuesOf(org, node)
      }
      after = page.pageInfo.endCursor
    } while (page.pageInfo.hasNextPage)
    return issuesByRepo
  }

  private suspend fun repositoriesPage(org: GhOrgLogin, after: String?): GraphQlRepositoriesDto =
      graphQl
          .query(
              orgReposDocument,
              buildJsonObject {
                put("login", org.value)
                put("after", after)
              },
              tokenProvider,
          )
          .decode<GraphQlOrganizationDataDto>()
          .organization
          .repositories

  // A repository with more open issues than one page holds is followed on its own, so the org query
  // stays one round trip in the case where every repository fits.
  private suspend fun allIssuesOf(org: GhOrgLogin, repo: GraphQlRepositoryDto): List<GhIssue> {
    val issues = repo.issues.nodes.map { it.toGhIssue() }.toMutableList()
    var page = repo.issues
    while (page.pageInfo.hasNextPage) {
      page = issuesPage(org, repo.name, page.pageInfo.endCursor)
      issues += page.nodes.map { it.toGhIssue() }
    }
    return issues
  }

  private suspend fun issuesPage(
      org: GhOrgLogin,
      repoName: String,
      after: String?,
  ): GraphQlIssuesDto =
      graphQl
          .query(
              repoIssuesDocument,
              buildJsonObject {
                put("owner", org.value)
                put("name", repoName)
                put("after", after)
              },
              tokenProvider,
          )
          .decode<GraphQlRepositoryDataDto>()
          .repository
          .issues
}

private inline fun <reified T> JsonObject.decode(): T = gitHubJson.decodeFromJsonElement<T>(this)

@Serializable private class GraphQlOrganizationDataDto(val organization: GraphQlOrganizationDto)

@Serializable private class GraphQlOrganizationDto(val repositories: GraphQlRepositoriesDto)

@Serializable
private class GraphQlRepositoriesDto(
    val pageInfo: GraphQlPageInfoDto,
    val nodes: List<GraphQlRepositoryDto>,
)

@Serializable
private class GraphQlRepositoryDto(
    val databaseId: Long,
    val name: String,
    val issues: GraphQlIssuesDto,
)

@Serializable private class GraphQlRepositoryDataDto(val repository: GraphQlRepositoryIssuesDto)

@Serializable private class GraphQlRepositoryIssuesDto(val issues: GraphQlIssuesDto)

@Serializable
private class GraphQlIssuesDto(val pageInfo: GraphQlPageInfoDto, val nodes: List<GraphQlIssueDto>)

@Serializable
private class GraphQlPageInfoDto(val hasNextPage: Boolean, val endCursor: String? = null)

@Serializable
private class GraphQlIssueDto(val number: Int, val title: String, val labels: GraphQlLabelsDto)

@Serializable private class GraphQlLabelsDto(val nodes: List<GraphQlLabelDto>)

@Serializable private class GraphQlLabelDto(val name: String)

private fun GraphQlIssueDto.toGhIssue(): GhIssue =
    GhIssue(number = number, title = title, labels = labels.nodes.map { it.name })
