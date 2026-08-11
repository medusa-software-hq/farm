package software.medusa.farm.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.CounterId
import software.medusa.farm.shared.CounterStore
import software.medusa.farm.shared.FibonacciStore
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.v1.DecrementRequest
import software.medusa.farm.v1.DecrementResponse
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.FibonacciNumber
import software.medusa.farm.v1.GetCountRequest
import software.medusa.farm.v1.GetCountResponse
import software.medusa.farm.v1.IncrementRequest
import software.medusa.farm.v1.IncrementResponse
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

private val mainCounterId = CounterId("main")

class FarmServiceImpl(
    private val counterStore: CounterStore,
    private val fibonacciStore: FibonacciStore,
    private val fibonacciStarter: FibonacciStarter,
    private val linkedOrgStore: LinkedOrgStore,
    private val gitHubApp: GitHubApp,
) : FarmServiceGrpcKt.FarmServiceCoroutineImplBase() {
  override suspend fun getCount(request: GetCountRequest): GetCountResponse =
      GetCountResponse.newBuilder().setCount(counterStore.getCount(mainCounterId)).build()

  override suspend fun increment(request: IncrementRequest): IncrementResponse =
      IncrementResponse.newBuilder()
          .setCount(counterStore.incrementAndGetCount(mainCounterId))
          .build()

  override suspend fun decrement(request: DecrementRequest): DecrementResponse =
      DecrementResponse.newBuilder()
          .setCount(counterStore.decrementAndGetCount(mainCounterId))
          .build()

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
  // app can reach. Unconfigured GitHub App -> UNIMPLEMENTED from the collaborator, nothing stored.
  override suspend fun linkOrg(request: LinkOrgRequest): LinkOrgResponse {
    val orgLogin = GhOrgLogin(request.orgLogin)
    val installationId = gitHubApp.resolveInstallationId(orgLogin)
    linkedOrgStore.link(installationId.value, orgLogin.value)
    val repositories = gitHubApp.listRepositories(orgLogin)
    return LinkOrgResponse.newBuilder()
        .setInstallationId(installationId.value)
        .addAllRepositories(repositories.map { it.value })
        .build()
  }

  // Steady-state read across every linked org; the collaborator reuses cached installation clients.
  // Unconfigured GitHub App -> UNIMPLEMENTED, so the web view degrades to a quiet empty state.
  override suspend fun listRepositories(
      request: ListRepositoriesRequest
  ): ListRepositoriesResponse =
      ListRepositoriesResponse.newBuilder()
          .addAllRepositories(
              gitHubApp.listAllRepositories().map { repository ->
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
