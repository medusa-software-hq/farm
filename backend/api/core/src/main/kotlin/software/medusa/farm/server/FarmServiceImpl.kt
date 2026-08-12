package software.medusa.farm.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.IssueStore
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.RepoStore
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.Issue as IssueProto
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.LinkOrgResponse
import software.medusa.farm.v1.LinkedOrg as LinkedOrgProto
import software.medusa.farm.v1.ListIssuesRequest
import software.medusa.farm.v1.ListIssuesResponse
import software.medusa.farm.v1.ListLinkedOrgsRequest
import software.medusa.farm.v1.ListLinkedOrgsResponse
import software.medusa.farm.v1.ListRepositoriesRequest
import software.medusa.farm.v1.ListRepositoriesResponse
import software.medusa.farm.v1.Repository
import software.medusa.farm.v1.SyncRepositoriesRequest
import software.medusa.farm.v1.SyncRepositoriesResponse

class FarmServiceImpl(
    private val linkedOrgStore: LinkedOrgStore,
    private val repoStore: RepoStore,
    private val issueStore: IssueStore,
    private val gitHubOrgs: GitHubOrgService,
    private val syncAllStarter: SyncAllStarter,
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

  // Steady-state read from the synced issues table: active (open) issues across every linked org,
  // no live GitHub call. Each row carries its repo's full name, so no repo join is needed.
  override suspend fun listIssues(request: ListIssuesRequest): ListIssuesResponse {
    val installationIds = linkedOrgStore.list().map { it.installationId }
    return ListIssuesResponse.newBuilder()
        .addAllIssues(
            issueStore.listActiveForOrgs(installationIds).map { issue ->
              IssueProto.newBuilder()
                  .setRepoFullName(issue.repoFullName)
                  .setNumber(issue.number)
                  .setTitle(issue.title)
                  .build()
            }
        )
        .build()
  }

  // Kicks the all-orgs sweep on demand — the same workflow the hourly schedule runs. The blocking
  // Temporal start runs off the request thread; an unreachable Temporal surfaces as a gRPC error so
  // the caller knows the sync did not start (unlike the best-effort on-link trigger). Returns as
  // soon as the sweep is started; the repos land as the worker processes it.
  override suspend fun syncRepositories(
      request: SyncRepositoriesRequest
  ): SyncRepositoriesResponse {
    withContext(Dispatchers.IO) { syncAllStarter.start() }
    return SyncRepositoriesResponse.getDefaultInstance()
  }
}
