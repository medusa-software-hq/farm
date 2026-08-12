package software.medusa.farm.server

import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.RepoStore
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.LinkOrgResponse
import software.medusa.farm.v1.LinkedOrg as LinkedOrgProto
import software.medusa.farm.v1.ListLinkedOrgsRequest
import software.medusa.farm.v1.ListLinkedOrgsResponse
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

  // The link state itself: which orgs are linked and under which installation. Surfaced on its own
  // so it reads as linked even before the first repo sync has populated the repos table.
  override suspend fun listLinkedOrgs(request: ListLinkedOrgsRequest): ListLinkedOrgsResponse =
      ListLinkedOrgsResponse.newBuilder()
          .addAllOrgs(
              linkedOrgStore.list().map { org ->
                LinkedOrgProto.newBuilder()
                    .setOrgLogin(org.orgLogin)
                    .setInstallationId(org.installationId)
                    .build()
              }
          )
          .build()

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
