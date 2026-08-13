package software.medusa.farm.worker

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.FakeGitHubServer
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.TestAppKey
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.InMemorySessionStore
import software.medusa.farm.shared.ProcessIssueWorkflow
import software.medusa.farm.shared.SessionState

/** Drives the fake issue-processing workflow against Temporal's test server and a fake GitHub. */
class ProcessIssueWorkflowTest {
  private val installationId = 100L
  private val appKey = TestAppKey()

  // Flip to make GitHub reject comment posts, so the failure path can be exercised.
  private var failComments = false

  private val server = FakeGitHubServer(::handle)
  private val sessions = InMemorySessionStore(Clock.systemUTC())

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

  init {
    val appApiClient =
        GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl)
    val clientProvider =
        GhProperInstallationApiClientProvider(appApiClient, baseUrl = server.baseUrl)
    val worker = env.newWorker(FarmWorker.TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(ProcessIssueWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(ProcessIssueActivitiesImpl(clientProvider, sessions))
    env.start()
  }

  @AfterTest
  fun tearDown() {
    env.close()
    server.close()
  }

  private fun process() {
    env.workflowClient
        .newWorkflowStub(
            ProcessIssueWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(FarmWorker.TASK_QUEUE).build(),
        )
        .process(installationId, 1L, "acme/one", 7, "Fix the thing")
  }

  @Test
  fun `opens a session, posts two comments, and completes the session`() {
    process()

    val session = runBlocking { sessions.listForOrgs(listOf(installationId)) }.single()
    assertEquals(SessionState.COMPLETED, session.state)
    assertEquals("acme/one", session.repoFullName)
    assertEquals("Fix the thing", session.title)

    val commentPosts =
        server.requests.count { it.method == "POST" && it.pathAndQuery.endsWith("/comments") }
    assertEquals(2, commentPosts)
  }

  @Test
  fun `marks the session failed when the work cannot complete`() {
    failComments = true
    // The workflow fails after the comment post exhausts its retries; swallow that here.
    runCatching { process() }

    val session = runBlocking { sessions.listForOrgs(listOf(installationId)) }.single()
    assertEquals(SessionState.FAILED, session.state)
  }

  private fun handle(request: FakeGitHubServer.Request): FakeGitHubServer.Response {
    val path = request.pathAndQuery.substringBefore('?')
    return when {
      path.endsWith("/access_tokens") ->
          FakeGitHubServer.Response(
              201,
              """{"token": "tok", "expires_at": "2999-01-01T00:00:00Z"}""",
          )
      path.endsWith("/comments") ->
          if (failComments) FakeGitHubServer.Response(500, "boom")
          else FakeGitHubServer.Response(201, """{"id": 1}""")
      else -> FakeGitHubServer.Response(404, "unexpected ${request.pathAndQuery}")
    }
  }
}
