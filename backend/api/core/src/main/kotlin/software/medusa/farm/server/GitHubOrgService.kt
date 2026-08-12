package software.medusa.farm.server

import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.LinkedOrgStore

/**
 * Farm's GitHub App collaborator for org linking: resolves an org's installation id once, at link
 * time, remembers it, then triggers a background sync of that installation's repos into the
 * Farm-owned repos table. The repo fetch itself lives in the worker (see the worker module's
 * repo-sync activity), so steady-state reads never touch GitHub — they read the synced table.
 */
class GitHubOrgService(
    private val appApiClient: GhAppApiClient,
    private val linkedOrgStore: LinkedOrgStore,
    private val repoSyncStarter: RepoSyncStarter,
) {
  /** Resolves and stores the org's installation, kicks off its repo sync, and returns the id. */
  suspend fun linkOrg(orgLogin: GhOrgLogin): GhInstallationId {
    val installationId = appApiClient.resolveInstallationId(orgLogin)
    linkedOrgStore.link(installationId.value, orgLogin.value)
    // Best-effort: the starter swallows a Temporal/worker outage, so a down worker never fails the
    // link. The org is already stored; the next sync will populate its repos.
    repoSyncStarter.start(installationId.value)
    return installationId
  }
}
