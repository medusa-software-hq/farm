package software.medusa.farm.worker

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.FakeGitHubServer
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.TestAppKey
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.InMemoryIssueStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.InMemoryRepoStore
import software.medusa.farm.shared.RepoSyncWorkflow

/** Drives the repo-sync workflow against Temporal's in-memory test server and a fake GitHub. */
class RepoSyncWorkflowTest {
  private val installationId = 100L
  private val appKey = TestAppKey()

  // The installation's current repo full names; the fake serves this, the test mutates it.
  private var currentRepos = listOf("acme/one", "acme/two")

  private val server = FakeGitHubServer(::handle)

  private val env =
      TestWorkflowEnvironment.newInstance(
          TestEnvironmentOptions.newBuilder()
              .setWorkflowClientOptions(
                  WorkflowClientOptions.newBuilder()
                      .setDataConverter(FarmDataConverter.instance)
                      .build()
              )
              .build()
      )

  // The store reads "now" from the same virtual clock the workflow captures its watermark from, so
  // the soft-orphan comparison is meaningful under time-skipping.
  private val store = InMemoryRepoStore(EnvClock(env))

  init {
    val appApiClient =
        GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl)
    val clientProvider =
        GhProperInstallationApiClientProvider(appApiClient, baseUrl = server.baseUrl)
    val worker = env.newWorker(FarmWorker.DEFAULT_TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(RepoSyncWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(
        RepoSyncActivitiesImpl(
            clientProvider,
            store,
            InMemoryIssueStore(EnvClock(env)),
            InMemoryLinkedOrgStore(),
            env.workflowClient,
            FarmWorker.DEFAULT_TASK_QUEUE,
        )
    )
    env.start()
  }

  @AfterTest
  fun tearDown() {
    env.close()
    server.close()
  }

  private fun sync() {
    env.workflowClient
        .newWorkflowStub(
            RepoSyncWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(FarmWorker.DEFAULT_TASK_QUEUE).build(),
        )
        .sync(installationId)
  }

  private fun activeFullNames(): List<String> = runBlocking {
    store.listActive(installationId).map { it.fullName }
  }

  @Test
  fun `fetches and reconciles the installation's repos into the store`() {
    sync()
    assertEquals(listOf("acme/one", "acme/two"), activeFullNames())
  }

  @Test
  fun `orphans a repo that is gone on the next sync`() {
    sync()
    env.sleep(Duration.ofMinutes(1))
    currentRepos = listOf("acme/one")
    sync()
    assertEquals(listOf("acme/one"), activeFullNames())
  }

  @Test
  fun `an empty-but-successful fetch never orphans`() {
    sync()
    env.sleep(Duration.ofMinutes(1))
    currentRepos = emptyList()
    sync()
    assertEquals(listOf("acme/one", "acme/two"), activeFullNames())
  }

  private fun handle(request: FakeGitHubServer.Request): FakeGitHubServer.Response {
    val path = request.pathAndQuery.substringBefore('?')
    return when {
      path.endsWith("/installation") ->
          FakeGitHubServer.Response(200, """{"id": $installationId}""")
      path.endsWith("/access_tokens") ->
          FakeGitHubServer.Response(
              201,
              """{"token": "tok", "expires_at": "2999-01-01T00:00:00Z"}""",
          )
      path == "/installation/repositories" -> {
        val body =
            currentRepos.joinToString(",") {
              val name = it.substringAfter('/')
              """{"id": ${repoId(it)}, "full_name": "$it", "name": "$name", """ +
                  """"private": false, "default_branch": "main"}"""
            }
        FakeGitHubServer.Response(
            200,
            """{"total_count": ${currentRepos.size}, "repositories": [$body]}""",
        )
      }
      // The fetch reads every repo's issues from the org query. This test exercises the repo
      // reconcile, so the org serves the same repos with no issues open on any of them.
      path == "/graphql" -> {
        val nodes =
            currentRepos.joinToString(",") {
              """{"databaseId": ${repoId(it)}, "name": "${it.substringAfter('/')}", """ +
                  """"issues": {"pageInfo": {"hasNextPage": false}, "nodes": []}}"""
            }
        FakeGitHubServer.Response(
            200,
            """{"data": {"organization": {"repositories": """ +
                """{"pageInfo": {"hasNextPage": false}, "nodes": [$nodes]}}}}""",
        )
      }
      else -> FakeGitHubServer.Response(404, "unexpected ${request.pathAndQuery}")
    }
  }

  /** The id GitHub would have for a repo, matched between the listing and the org query. */
  private fun repoId(fullName: String): Long = fullName.hashCode().toLong() and 0x7fffffff

  private class EnvClock(private val env: TestWorkflowEnvironment) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(env.currentTimeMillis())

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
  }
}
