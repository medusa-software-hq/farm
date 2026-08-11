package software.medusa.farm.server

import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.shared.LinkedOrgStore

/**
 * [GitHubApp] backed by a real, configured app: resolves installations through the App client, and
 * reads repositories through per-org installation clients the provider hands out (and caches), so
 * the steady-state read reuses tokens across calls instead of re-minting each time.
 */
class MintingGitHubApp(
    private val appApiClient: GhAppApiClient,
    private val clientProvider: GhInstallationApiClientProvider,
    private val linkedOrgStore: LinkedOrgStore,
) : GitHubApp {
  override suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId =
      appApiClient.resolveInstallationId(orgLogin)

  override suspend fun listRepositories(orgLogin: GhOrgLogin): List<GhRepoFullName> =
      clientProvider.provideForOrg(orgLogin).listInstallationRepositories()

  // N+1: one issue call per repository. Fine at demo scale, where each org has a handful of repos
  // and each issue call is bounded to a few recent issues.
  override suspend fun listAllRepositories(): List<OrgRepository> =
      linkedOrgStore.list().flatMap { org ->
        val orgLogin = GhOrgLogin(org.orgLogin)
        val client = clientProvider.provideForOrg(orgLogin)
        client.listInstallationRepositories().map { repo ->
          OrgRepository(orgLogin, repo, client.listIssues(repo))
        }
      }
}
