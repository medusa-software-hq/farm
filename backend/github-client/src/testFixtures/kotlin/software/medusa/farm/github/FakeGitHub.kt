package software.medusa.farm.github

/**
 * A data-scripted GitHub for [FakeGitHubServer] to answer from: give it the installations, their
 * repositories, and (optionally) each repo's issues, and it routes the App, installation, resource
 * and GraphQL endpoints the clients call. The token it mints for an installation encodes that
 * installation's id, so later repo and issue calls can be attributed back to it.
 *
 * An org holds every repository given to any of its installations, so scripting two installations
 * over one org is how a repository the caller cannot reach is put in front of it.
 */
class FakeGitHub(
    private val installationIdsByOrg: Map<GhOrgLogin, GhInstallationId>,
    private val reposByInstallation: Map<GhInstallationId, List<GhRepoFullName>>,
    private val issuesByRepo: Map<GhRepoFullName, List<GhIssue>> = emptyMap(),
) {
  val handler: (FakeGitHubServer.Request) -> FakeGitHubServer.Response = { request ->
    val path = request.pathAndQuery.substringBefore('?')
    when {
      path.startsWith("/orgs/") && path.endsWith("/installation") -> {
        val org = GhOrgLogin(path.removePrefix("/orgs/").removeSuffix("/installation"))
        val id = installationIdsByOrg.getValue(org)
        FakeGitHubServer.Response(200, """{"id": ${id.value}}""")
      }
      path.endsWith("/access_tokens") -> {
        val id = path.removePrefix("/app/installations/").removeSuffix("/access_tokens")
        FakeGitHubServer.Response(
            201,
            """{"token": "tok-$id", "expires_at": "2999-01-01T00:00:00Z"}""",
        )
      }
      path == "/installation/repositories" -> {
        val id = GhInstallationId(request.authorization!!.removePrefix("Bearer tok-").toLong())
        val allRepos = reposByInstallation.getValue(id)
        val perPage = queryParam(request.pathAndQuery, "per_page")?.toInt() ?: allRepos.size
        val page = queryParam(request.pathAndQuery, "page")?.toInt() ?: 1
        val pageRepos = allRepos.drop((page - 1) * perPage).take(perPage)
        val body =
            pageRepos.joinToString(",") {
              val name = it.value.substringAfter('/')
              """{"id": ${repoId(it)}, "full_name": "${it.value}", "name": "$name", """ +
                  """"private": false, "default_branch": "main"}"""
            }
        // Advertise a next page via the Link header (as GitHub does) whenever more remain, so the
        // client walks pages by following it rather than by counting.
        val hasNext = page * perPage < allRepos.size
        val headers =
            if (hasNext)
                mapOf(
                    "Link" to
                        "</installation/repositories?per_page=$perPage&page=${page + 1}>; rel=\"next\""
                )
            else emptyMap()
        FakeGitHubServer.Response(
            200,
            """{"total_count": ${allRepos.size}, "repositories": [$body]}""",
            headers,
        )
      }
      path.startsWith("/repos/") && path.endsWith("/issues") -> {
        val repo = GhRepoFullName(path.removePrefix("/repos/").removeSuffix("/issues"))
        val issues = issuesByRepo[repo].orEmpty()
        val body =
            issues.joinToString(",") { issue ->
              val labels = issue.labels.joinToString(",") { """{"name": "$it"}""" }
              """{"number": ${issue.number}, "title": "${issue.title}", "labels": [$labels]}"""
            }
        FakeGitHubServer.Response(200, "[$body]")
      }
      path.startsWith("/repos/") && path.endsWith("/comments") ->
          FakeGitHubServer.Response(201, """{"id": 1}""")
      // The org query, answered as GitHub answers it: from the org rather than from the asking
      // installation, so a repo the org has and the caller cannot reach is offered up too.
      path == "/graphql" -> {
        val org = GhOrgLogin(variable(request.body, "login"))
        val nodes =
            reposByInstallation.values
                .flatten()
                .distinct()
                .filter { it.owner == org }
                .joinToString(",") { repositoryNode(it) }
        FakeGitHubServer.Response(
            200,
            """{"data": {"organization": {"repositories": ${connection(nodes)}}}}""",
        )
      }
      else -> FakeGitHubServer.Response(404, "unexpected ${request.pathAndQuery}")
    }
  }

  private fun repositoryNode(repo: GhRepoFullName): String {
    val issues =
        issuesByRepo[repo].orEmpty().joinToString(",") { issue ->
          val labels = issue.labels.joinToString(",") { """{"name": "$it"}""" }
          """{"number": ${issue.number}, "title": "${issue.title}", """ +
              """"labels": {"nodes": [$labels]}}"""
        }
    return """{"databaseId": ${repoId(repo)}, "name": "${repo.value.substringAfter('/')}", """ +
        """"issues": ${connection(issues)}}"""
  }

  /** A stable synthetic id derived from the full name, so a repo keeps its id across fetches. */
  private fun repoId(repo: GhRepoFullName): Long = repo.value.hashCode().toLong() and 0x7fffffff

  private fun queryParam(pathAndQuery: String, name: String): String? =
      Regex("[?&]$name=([^&]+)").find(pathAndQuery)?.groupValues?.get(1)

  // The document quotes no string, so the only quoted value under this name is the variable's.
  private fun variable(body: String, name: String): String =
      Regex(""""$name"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]

  /** A GraphQL connection holding [nodes] whole — this fake never splits one across pages. */
  private fun connection(nodes: String): String =
      """{"pageInfo": {"hasNextPage": false, "endCursor": null}, "nodes": [$nodes]}"""
}
