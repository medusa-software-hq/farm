package software.medusa.farm.server

import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhRepoFullName

/** The outcome of linking an org: its resolved installation id and the repos the app can reach. */
class OrgLink(
    val installationId: GhInstallationId,
    val repositories: List<GhRepoFullName>,
)
