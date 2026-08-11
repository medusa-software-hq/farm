package software.medusa.farm.server

import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.LinkedOrgStore

/**
 * Farm's GitHub App collaborator: the App client, the per-installation client provider, and the
 * linked-org store as one unit, with the org-linking and repository-listing orchestration over
 * them. An org's installation id is resolved exactly once, at link time; every later read keys the
 * provider off the stored id, so steady state never touches the App-management endpoints.
 */
class GitHubOrgService(
    private val appApiClient: GhAppApiClient,
    private val clientProvider: GhInstallationApiClientProvider,
    private val linkedOrgStore: LinkedOrgStore,
) {
  suspend fun linkOrg(orgLogin: GhOrgLogin): OrgLink {
    val installationId = appApiClient.resolveInstallationId(orgLogin)
    linkedOrgStore.link(installationId.value, orgLogin.value)
    val repositories =
        clientProvider.provideForInstallation(installationId).listInstallationRepositories()
    return OrgLink(installationId, repositories)
  }

  // N+1: one issue call per repository. Fine at demo scale, where each org has a handful of repos
  // and each issue call is bounded to a few recent issues.
  suspend fun listRepositories(): List<OrgRepository> =
      linkedOrgStore.list().flatMap { org ->
        val client = clientProvider.provideForInstallation(GhInstallationId(org.installationId))
        client.listInstallationRepositories().map { repo ->
          OrgRepository(GhOrgLogin(org.orgLogin), repo, client.listIssues(repo))
        }
      }
}
