package software.medusa.farm.github

/**
 * A data-scripted GitHub for [FakeGitHubServer] to answer from: give it the installations, their
 * repositories, and (optionally) each repo's issues, and it routes the App, installation, and
 * resource endpoints the clients call. The token it mints for an installation encodes that
 * installation's id, so later repo and issue calls can be attributed back to it.
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
              // A stable synthetic numeric id derived from the full name, so a repo keeps its id
              // across fetches (only a rename would change it).
              val repoId = it.value.hashCode().toLong() and 0x7fffffff
              """{"id": $repoId, "full_name": "${it.value}", "name": "$name", """ +
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
            issues.joinToString(",") { """{"number": ${it.number}, "title": "${it.title}"}""" }
        FakeGitHubServer.Response(200, "[$body]")
      }
      else -> FakeGitHubServer.Response(404, "unexpected ${request.pathAndQuery}")
    }
  }

  private fun queryParam(pathAndQuery: String, name: String): String? =
      Regex("[?&]$name=([^&]+)").find(pathAndQuery)?.groupValues?.get(1)
}
