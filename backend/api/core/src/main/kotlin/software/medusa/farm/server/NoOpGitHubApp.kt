package software.medusa.farm.server

import io.grpc.Status
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhRepoFullName

/** Stands in when the GitHub App isn't configured (local dev, or the API before the app exists). */
object NoOpGitHubApp : GitHubApp {
  override suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId = unavailable()

  override suspend fun listRepositories(orgLogin: GhOrgLogin): List<GhRepoFullName> = unavailable()

  override suspend fun listAllRepositories(): List<OrgRepository> = unavailable()

  private fun unavailable(): Nothing =
      throw Status.UNIMPLEMENTED.withDescription("The GitHub App is not configured.").asException()
}
