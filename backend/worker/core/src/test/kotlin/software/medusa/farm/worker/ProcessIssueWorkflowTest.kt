package software.medusa.farm.worker

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.FakeGitHubServer
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.TestAppKey
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.InMemorySessionStore
import software.medusa.farm.shared.ProcessIssueWorkflow
import software.medusa.farm.shared.SessionState

/**
 * Drives the issue-processing workflow against Temporal's test server, GitHub, and a fake attempt.
 */
class ProcessIssueWorkflowTest {
  private val installationId = 100L
  private val appKey = TestAppKey()

  // Flip to make the attempt fail, so the failure path can be exercised.
  private var failAttempt = false

  // What the fake GitHub reports the pull request as, and when it says it was merged.
  private var pullRequestState = "open"
  private var pullRequestMerged = false

  private val server = FakeGitHubServer(::handle)
  private val sessions = InMemorySessionStore(Clock.systemUTC())

  /** A stand-in [PublishActivities]: reports a PR, or throws when [failAttempt] is set. */
  private inner class FakePublishActivities : PublishActivities {
    override fun attemptIssue(
        sessionId: String,
        installationId: Long,
        repoFullName: String,
        number: Int,
        title: String,
    ): IssueAttemptOutcome {
      check(!failAttempt) { "attempt boom" }
      return IssueAttemptOutcome(
          pullRequestUrl = "https://github.com/acme/one/pull/12",
          pullRequestNumber = 12,
          pullRequestHeadSha = "abc123",
      )
    }
  }

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
    worker.registerActivitiesImplementations(
        ProcessIssueActivitiesImpl(clientProvider, sessions),
        FakePublishActivities(),
    )
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
  fun `opens a session, posts the attempt result, and completes the session`() {
    process()

    val session = runBlocking { sessions.listForOrgs(listOf(installationId)) }.single()
    assertEquals(SessionState.COMPLETED, session.state)
    assertEquals("acme/one", session.repoFullName)
    assertEquals("Fix the thing", session.title)
    // The opened PR is recorded on the session.
    assertEquals("https://github.com/acme/one/pull/12", session.pullRequest?.url)

    val commentPosts =
        server.requests.count { it.method == "POST" && it.pathAndQuery.endsWith("/comments") }
    assertEquals(1, commentPosts)
  }

  @Test
  fun `the session waits for the pull request and records the merge`() {
    pullRequestState = "open"
    pullRequestMerged = false

    // The gate polls on a durable timer; the test server skips the waiting rather than living
    // through it, and the merge lands between two polls.
    env.registerDelayedCallback(Duration.ofMinutes(25)) {
      pullRequestState = "closed"
      pullRequestMerged = true
    }

    process()

    val session = runBlocking { sessions.listForOrgs(listOf(installationId)) }.single()
    assertEquals(SessionState.COMPLETED, session.state)
    assertEquals(
        Instant.parse("2026-08-19T10:00:00Z"),
        session.pullRequest?.mergedAt,
        "the merge GitHub reported was not recorded",
    )
  }

  @Test
  fun `a pull request closed without merging ends the session unmerged`() {
    pullRequestState = "open"
    pullRequestMerged = false
    env.registerDelayedCallback(Duration.ofMinutes(15)) { pullRequestState = "closed" }

    process()

    val session = runBlocking { sessions.listForOrgs(listOf(installationId)) }.single()
    // Over, but not successful — which is read from the merge, not from the session ending.
    assertEquals(SessionState.COMPLETED, session.state)
    assertNull(session.pullRequest?.mergedAt)
  }

  @Test
  fun `a pull request nobody touches stops being waited for`() {
    pullRequestState = "open"
    pullRequestMerged = false

    process()

    val session = runBlocking { sessions.listForOrgs(listOf(installationId)) }.single()
    assertEquals(SessionState.COMPLETED, session.state)
    assertNull(session.pullRequest?.mergedAt)
  }

  @Test
  fun `marks the session failed when the attempt cannot complete`() {
    failAttempt = true
    // The workflow fails after the attempt activity exhausts its retries; swallow that here.
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
      path.endsWith("/comments") -> FakeGitHubServer.Response(201, """{"id": 1}""")
      path.contains("/pulls/") ->
          FakeGitHubServer.Response(
              200,
              """{"number": 12, "html_url": "https://github.com/acme/one/pull/12",
                 "state": "$pullRequestState", "merged": $pullRequestMerged,
                 "merged_at": ${if (pullRequestMerged) "\"2026-08-19T10:00:00Z\"" else "null"},
                 "head": {"sha": "abc123"}}""",
          )
      else -> FakeGitHubServer.Response(404, "unexpected ${request.pathAndQuery}")
    }
  }
}
