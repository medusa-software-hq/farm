package software.medusa.farm.worker

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import software.medusa.farm.claude.CldAssistantStep
import software.medusa.farm.claude.CldCost
import software.medusa.farm.claude.CldEffort
import software.medusa.farm.claude.CldEngine
import software.medusa.farm.claude.CldModelId
import software.medusa.farm.claude.CldRunResult
import software.medusa.farm.claude.CldRunStatus
import software.medusa.farm.claude.CldSessionConfig
import software.medusa.farm.claude.CldSessionEvent
import software.medusa.farm.claude.CldSessionId
import software.medusa.farm.claude.CldSessionInfo
import software.medusa.farm.claude.CldSessionScope
import software.medusa.farm.gitcli.GitCli
import software.medusa.farm.gitcli.GitCliAuthor
import software.medusa.farm.github.FakeGitHubServer
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.TestAppKey
import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunEntry
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentWarning
import software.medusa.farm.shared.AttemptNumbering
import software.medusa.farm.shared.InMemorySessionStore
import software.medusa.farm.shared.SessionRunAttempt
import software.medusa.farm.shared.SessionStore

class PublishActivitiesImplTest {
  private val appKey = TestAppKey()
  private val author = GitCliAuthor("Farm", "farm@medusa.software")
  private val sessions = InMemorySessionStore(Clock.systemUTC())

  private class FakeSummarizer(private val summary: RunSummary) : RunSummarizer {
    override suspend fun summarize(log: AgentRunLog): RunSummary = summary
  }

  /** A summarizer whose backend will not answer. */
  private object UnreachableSummarizer : RunSummarizer {
    override suspend fun summarize(log: AgentRunLog): RunSummary = throw RunSummaryGenerationError
  }

  private val available = FakeSummarizer(RunSummary("a summary"))

  /** Records the workspace it ran in, produces [events], and reports a clean session. */
  private class FakeEngine(private val events: List<CldSessionEvent> = emptyList()) : CldEngine {
    var ranIn: Path? = null

    override suspend fun <ResultT> runSession(
        config: CldSessionConfig,
        prompt: String,
        block: suspend CldSessionScope.() -> ResultT,
    ): ResultT {
      ranIn = config.workspacePath
      val scope =
          object : CldSessionScope {
            override val info =
                CldSessionInfo(
                    sessionId = CldSessionId("session-fake"),
                    modelId = CldModelId("claude-opus-5"),
                    availableToolSpecifiers = emptySet(),
                )

            override val eventChannel =
                Channel<CldSessionEvent>(Channel.UNLIMITED).apply {
                  events.forEach { trySend(it) }
                  close()
                }

            override suspend fun awaitResult(): CldRunResult =
                CldRunResult(
                    status = CldRunStatus.Success,
                    totalCost = CldCost(usdAmount = 0.0),
                    turnCount = 0,
                    sessionDuration = Duration.ZERO,
                )
          }
      return scope.block()
    }
  }

  /** Records the calls it received; [hasChanges] drives the diff check. */
  private class FakeGitCli(private val hasChanges: Boolean) : GitCli {
    val calls = mutableListOf<String>()
    var clonedInto: Path? = null

    override suspend fun clone(remoteUrl: String, into: Path, token: String) {
      calls += "clone"
      clonedInto = into
      Files.createDirectories(into)
    }

    override suspend fun currentBranch(repo: Path): String {
      calls += "currentBranch"
      return "trunk"
    }

    override suspend fun createBranch(repo: Path, branch: String) {
      calls += "createBranch:$branch"
    }

    override suspend fun checkout(repo: Path, branch: String) {
      calls += "checkout:$branch"
    }

    override suspend fun stageAll(repo: Path) {
      calls += "stageAll"
    }

    override suspend fun hasStagedChanges(repo: Path): Boolean {
      calls += "hasStagedChanges"
      return hasChanges
    }

    override suspend fun commit(
        repo: Path,
        message: String,
        author: GitCliAuthor,
        signingKey: String?,
    ) {
      calls += "commit"
    }

    override suspend fun push(repo: Path, branch: String, token: String) {
      calls += "push:$branch"
    }

    override suspend fun headSha(repo: Path): String {
      calls += "headSha"
      return "0".repeat(40)
    }
  }

  private class FixedAttemptNumbering(private val attempt: Int) : AttemptNumbering {
    override fun currentAttempt(): Int = attempt
  }

  /** Delegates to [delegate] while noting, in order, what it was asked to write. */
  private class RecordingSessionStore(private val delegate: SessionStore) :
      SessionStore by delegate {
    val writes = mutableListOf<String>()

    override suspend fun startRunAttempt(id: String, ordinal: Int, attempt: Int) {
      writes += "start:$attempt"
      delegate.startRunAttempt(id, ordinal, attempt)
    }

    override suspend fun appendRunEntry(
        id: String,
        ordinal: Int,
        attempt: Int,
        position: Int,
        entry: AgentRunEntry,
    ) {
      writes += "append:$position"
      delegate.appendRunEntry(id, ordinal, attempt, position, entry)
    }

    override suspend fun finishRunAttempt(
        id: String,
        ordinal: Int,
        attempt: Int,
        outcome: AgentRunOutcome,
        cost: AgentRunCost?,
        summary: String,
    ) {
      writes += "finish"
      delegate.finishRunAttempt(id, ordinal, attempt, outcome, cost, summary)
    }
  }

  private fun activities(
      gitCli: GitCli,
      engine: CldEngine,
      server: FakeGitHubServer,
      summarizer: RunSummarizer = available,
      sessionStore: SessionStore = sessions,
      attempt: Int = 1,
  ) =
      PublishActivitiesImpl(
          clientProvider =
              GhProperInstallationApiClientProvider(
                  GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl),
                  baseUrl = server.baseUrl,
              ),
          tokenMinter =
              GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl),
          engine = engine,
          gitCli = gitCli,
          sessionStore = sessionStore,
          attemptNumbering = FixedAttemptNumbering(attempt),
          summarizer = summarizer,
          commitAuthor = author,
          signingKey = null,
          claudeModel = CldModelId("claude-opus-4-5"),
          claudeEffort = CldEffort.High,
      )

  private fun handle(request: FakeGitHubServer.Request): FakeGitHubServer.Response {
    val path = request.pathAndQuery.substringBefore('?')
    return when {
      path.endsWith("/access_tokens") ->
          FakeGitHubServer.Response(
              201,
              """{"token": "tok", "expires_at": "2999-01-01T00:00:00Z"}""",
          )
      path.endsWith("/issues/7") ->
          FakeGitHubServer.Response(200, """{"number": 7, "body": "Please fix the thing."}""")
      path.endsWith("/pulls") ->
          FakeGitHubServer.Response(
              201,
              """{"number": 12, "html_url": "https://github.com/acme/one/pull/12", """ +
                  """"state": "open", "head": {"sha": "abc"}}""",
          )
      else -> FakeGitHubServer.Response(404, "unexpected ${request.pathAndQuery}")
    }
  }

  @Test
  fun `runs the agent on the clone and opens a PR when there are changes`() {
    FakeGitHubServer(::handle).use { server ->
      val gitCli = FakeGitCli(hasChanges = true)
      val engine = FakeEngine()

      val outcome =
          activities(gitCli, engine, server)
              .attemptIssue("session-1", 100L, "acme/one", 7, "Fix it")

      assertEquals("https://github.com/acme/one/pull/12", outcome.pullRequestUrl)
      assertEquals(gitCli.clonedInto, engine.ranIn, "the agent must run in the clone")
      assertContains(gitCli.calls, "clone")
      assertContains(gitCli.calls, "createBranch:farm/issue-7")
      assertContains(gitCli.calls, "commit")
      assertContains(gitCli.calls, "push:farm/issue-7")
      assertEquals(1, runBlocking { sessions.getRuns("session-1") }.size, "the run is recorded")
    }
  }

  @Test
  fun `the run is written as it happens, not once it is over`() {
    val store = RecordingSessionStore(sessions)
    val engine =
        FakeEngine(
            events =
                listOf(
                    CldSessionEvent.Step(CldAssistantStep(text = "first", toolUses = emptyList())),
                    CldSessionEvent.Warning("something is deprecated"),
                    CldSessionEvent.Step(CldAssistantStep(text = "second", toolUses = emptyList())),
                )
        )

    FakeGitHubServer(::handle).use { server ->
      activities(FakeGitCli(hasChanges = false), engine, server, sessionStore = store)
          .attemptIssue("session-1", 100L, "acme/one", 7, "Fix it")
    }

    // Opened, appended to entry by entry, and only then closed. A run written once at the end would
    // show the same log and none of this.
    assertEquals(
        listOf("start:1", "append:0", "append:1", "append:2", "finish"),
        store.writes,
    )

    val attempt =
        assertIs<SessionRunAttempt.Finished>(
            runBlocking { sessions.getRuns("session-1") }.single().attempts.single()
        )
    assertEquals(
        listOf<AgentRunEntry>(
            AgentStep(text = "first", toolActions = emptyList()),
            AgentWarning(text = "something is deprecated"),
            AgentStep(text = "second", toolActions = emptyList()),
        ),
        attempt.log.entries,
    )
  }

  @Test
  fun `a retry is a try of its own, and the one it retried is still there to read`() {
    val engine =
        FakeEngine(
            events =
                listOf(
                    CldSessionEvent.Step(
                        CldAssistantStep(text = "got here", toolUses = emptyList())
                    )
                )
        )

    FakeGitHubServer(::handle).use { server ->
      // The first try fails after the agent has run but before the run is closed, which is what a
      // crashed attempt looks like: entries written, nothing to say about how it went.
      assertFailsWith<RunSummaryGenerationError> {
        activities(
                FakeGitCli(hasChanges = false),
                engine,
                server,
                UnreachableSummarizer,
                attempt = 1,
            )
            .attemptIssue("session-1", 100L, "acme/one", 7, "Fix it")
      }

      activities(FakeGitCli(hasChanges = false), engine, server, attempt = 2)
          .attemptIssue("session-1", 100L, "acme/one", 7, "Fix it")
    }

    val attempts = runBlocking { sessions.getRuns("session-1") }.single().attempts
    assertEquals(listOf(1, 2), attempts.map { it.number })

    val crashed = assertIs<SessionRunAttempt.Abandoned>(attempts.first())
    assertEquals(
        listOf<AgentRunEntry>(AgentStep(text = "got here", toolActions = emptyList())),
        crashed.log.entries,
        "the crashed try lost what it had already done",
    )
    assertIs<SessionRunAttempt.Finished>(attempts.last())
  }

  @Test
  fun `raises when the run cannot be summarized, so Temporal retries`() {
    FakeGitHubServer(::handle).use { server ->
      val activities =
          activities(FakeGitCli(hasChanges = true), FakeEngine(), server, UnreachableSummarizer)

      assertFailsWith<RunSummaryGenerationError> {
        activities.attemptIssue("session-1", 100L, "acme/one", 7, "Fix it")
      }

      assertTrue(
          server.requests.none { it.pathAndQuery.endsWith("/pulls") },
          "the PR must not be opened when the run could not be recorded",
      )
    }
  }

  @Test
  fun `does not open a PR when the agent produced no changes`() {
    FakeGitHubServer(::handle).use { server ->
      val gitCli = FakeGitCli(hasChanges = false)

      val outcome =
          activities(gitCli, FakeEngine(), server)
              .attemptIssue("session-1", 100L, "acme/one", 7, "Fix it")

      assertNull(outcome.pullRequestUrl)
      assertFalse(gitCli.calls.any { it.startsWith("createBranch") }, "should not branch")
      assertFalse(gitCli.calls.any { it.startsWith("push") }, "should not push")
      assertTrue(
          server.requests.none { it.pathAndQuery.endsWith("/pulls") },
          "should not open a PR",
      )
    }
  }
}
