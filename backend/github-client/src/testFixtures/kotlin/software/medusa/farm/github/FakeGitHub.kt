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
        val repos = reposByInstallation.getValue(id)
        val body = repos.joinToString(",") { """{"full_name": "${it.value}"}""" }
        FakeGitHubServer.Response(
            200,
            """{"total_count": ${repos.size}, "repositories": [$body]}""",
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
}
