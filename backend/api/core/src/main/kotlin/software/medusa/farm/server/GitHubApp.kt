package software.medusa.farm.server

import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhRepoFullName

/**
 * The API's view of the Farm GitHub App. Kept behind an interface so the environment (a configured
 * app vs. no app yet) is chosen at the entry point, and so — when no app is configured — the GitHub
 * RPCs degrade to unavailable instead of taking the service down.
 */
interface GitHubApp {
  /** The installation id for [orgLogin]'s installation of the app. */
  suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId

  /** The repositories the app can reach in [orgLogin]. */
  suspend fun listRepositories(orgLogin: GhOrgLogin): List<GhRepoFullName>

  /**
   * Every repository reachable across all linked orgs, each tagged with its org and its recent
   * issues. Steady-state read: installation clients are cached per org and reused between calls.
   */
  suspend fun listAllRepositories(): List<OrgRepository>
}
