package software.medusa.farm.server

import io.grpc.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.FibonacciStore
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.FibonacciNumber
import software.medusa.farm.v1.Issue
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
    private val gitHubOrgs: GitHubOrgService?,
) : FarmServiceGrpcKt.FarmServiceCoroutineImplBase() {
  // The GitHub RPCs' shared guard: absent app -> UNIMPLEMENTED, nothing stored, other RPCs
  // unharmed.
  private fun gitHubOrgs(): GitHubOrgService =
      gitHubOrgs
          ?: throw Status.UNIMPLEMENTED.withDescription("GitHub App not configured")
              .asRuntimeException()

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

  // Links an org to the Farm app: discover its installation, remember it, then report the repos the
  // app can reach.
  override suspend fun linkOrg(request: LinkOrgRequest): LinkOrgResponse {
    val link = gitHubOrgs().linkOrg(GhOrgLogin(request.orgLogin))
    return LinkOrgResponse.newBuilder()
        .setInstallationId(link.installationId.value)
        .addAllRepositories(link.repositories.map { it.value })
        .build()
  }

  // Steady-state read across every linked org; the collaborator reuses cached installation clients.
  override suspend fun listRepositories(
      request: ListRepositoriesRequest
  ): ListRepositoriesResponse =
      ListRepositoriesResponse.newBuilder()
          .addAllRepositories(
              gitHubOrgs().listRepositories().map { repository ->
                Repository.newBuilder()
                    .setOrgLogin(repository.orgLogin.value)
                    .setFullName(repository.fullName.value)
                    .addAllRecentIssues(
                        repository.recentIssues.map {
                          Issue.newBuilder().setNumber(it.number).setTitle(it.title).build()
                        }
                    )
                    .build()
              }
          )
          .build()
}
