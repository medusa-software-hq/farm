package software.medusa.farm.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.FibonacciStore
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.RepoStore
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.FibonacciNumber
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.LinkOrgResponse
import software.medusa.farm.v1.ListFibonacciRequest
import software.medusa.farm.v1.ListFibonacciResponse
import software.medusa.farm.v1.ListRepositoriesRequest
import software.medusa.farm.v1.ListRepositoriesResponse
import software.medusa.farm.v1.Repository
import software.medusa.farm.v1.StartFibonacciRequest
import software.medusa.farm.v1.StartFibonacciResponse

class FarmServiceImpl(
    private val fibonacciStore: FibonacciStore,
    private val fibonacciStarter: FibonacciStarter,
    private val linkedOrgStore: LinkedOrgStore,
    private val repoStore: RepoStore,
    private val gitHubOrgs: GitHubOrgService,
) : FarmServiceGrpcKt.FarmServiceCoroutineImplBase() {
  override suspend fun listFibonacci(request: ListFibonacciRequest): ListFibonacciResponse =
      ListFibonacciResponse.newBuilder()
          .addAllNumbers(
              fibonacciStore.list().map {
                FibonacciNumber.newBuilder()
                    .setIndex(it.index)
                    .setValue(it.value.toString())
                    .build()
              }
          )
          .build()

  // The blocking Temporal client call runs off the request thread; a Temporal-unreachable start is
  // surfaced as a gRPC error by the starter, failing only this call.
  override suspend fun startFibonacci(request: StartFibonacciRequest): StartFibonacciResponse {
    val workflowId = withContext(Dispatchers.IO) { fibonacciStarter.start(request.through) }
    return StartFibonacciResponse.newBuilder().setWorkflowId(workflowId).build()
  }

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
  // no live GitHub call. Works even with the GitHub App unconfigured.
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
