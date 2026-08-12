package software.medusa.farm.server

import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.RepoStore
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.LinkOrgResponse
import software.medusa.farm.v1.ListRepositoriesRequest
import software.medusa.farm.v1.ListRepositoriesResponse
import software.medusa.farm.v1.Repository

class FarmServiceImpl(
    private val linkedOrgStore: LinkedOrgStore,
    private val repoStore: RepoStore,
    private val gitHubOrgs: GitHubOrgService,
) : FarmServiceGrpcKt.FarmServiceCoroutineImplBase() {
  // Links an org to the Farm app: resolve and store its installation, kick off a background repo
  // sync, then report the repos already known for it (empty until the first sync lands).
  override suspend fun linkOrg(request: LinkOrgRequest): LinkOrgResponse {
    val installationId = gitHubOrgs.linkOrg(GhOrgLogin(request.orgLogin))
    val repositories = repoStore.listActive(installationId.value)
    return LinkOrgResponse.newBuilder()
        .setInstallationId(installationId.value)
        .addAllRepositories(repositories.map { it.fullName })
        .build()
  }

  // Steady-state read straight from the synced repos table: active repos across every linked org,
  // no live GitHub call.
  override suspend fun listRepositories(
      request: ListRepositoriesRequest
  ): ListRepositoriesResponse =
      ListRepositoriesResponse.newBuilder()
          .addAllRepositories(
              linkedOrgStore.list().flatMap { org ->
                repoStore.listActive(org.installationId).map { repo ->
                  Repository.newBuilder()
                      .setOrgLogin(org.orgLogin)
                      .setFullName(repo.fullName)
                      .build()
                }
              }
          )
          .build()
}
