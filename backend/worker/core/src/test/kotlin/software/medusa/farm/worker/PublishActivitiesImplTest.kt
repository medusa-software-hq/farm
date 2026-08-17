package software.medusa.farm.worker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import software.medusa.farm.claude.CldAgent
import software.medusa.farm.claude.CldCompletion
import software.medusa.farm.claude.CldProperSessionStore
import software.medusa.farm.claude.CldRun
import software.medusa.farm.claude.CldRunRequest
import software.medusa.farm.claude.CldRunResult
import software.medusa.farm.claude.CldStep
import software.medusa.farm.gitcli.GitCli
import software.medusa.farm.gitcli.GitCliAuthor
import software.medusa.farm.github.FakeGitHubServer
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.TestAppKey

class PublishActivitiesImplTest {
  private val appKey = TestAppKey()
  private val author = GitCliAuthor("Farm", "farm@medusa.software")

  /** Records the workspace it ran in, streams nothing, and reports a clean completion. */
  private class FakeAgent : CldAgent {
    var ranIn: Path? = null

    override suspend fun <T> run(request: CldRunRequest, consume: suspend (CldRun) -> T): T {
      ranIn = request.workspace
      val run =
          object : CldRun {
            override val steps = emptyFlow<CldStep>()
            override val result =
                CompletableDeferred(
                    CldRunResult(request.session.sessionId, CldCompletion.Ok, cost = null)
                )
          }
      return consume(run)
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

  private fun activities(gitCli: GitCli, agent: CldAgent, server: FakeGitHubServer) =
      PublishActivitiesImpl(
          clientProvider =
              GhProperInstallationApiClientProvider(
                  GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl),
                  baseUrl = server.baseUrl,
              ),
          tokenMinter =
              GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl),
          agent = agent,
          gitCli = gitCli,
          sessionStore = CldProperSessionStore(Files.createTempDirectory("publish-it")),
          commitAuthor = author,
          signingKey = null,
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
      val agent = FakeAgent()

      val outcome = activities(gitCli, agent, server).attemptIssue(100L, "acme/one", 7, "Fix it")

      assertEquals("https://github.com/acme/one/pull/12", outcome.pullRequestUrl)
      assertEquals(gitCli.clonedInto, agent.ranIn, "the agent must run in the clone")
      assertContains(gitCli.calls, "clone")
      assertContains(gitCli.calls, "createBranch:farm/issue-7")
      assertContains(gitCli.calls, "commit")
      assertContains(gitCli.calls, "push:farm/issue-7")
    }
  }

  @Test
  fun `does not open a PR when the agent produced no changes`() {
    FakeGitHubServer(::handle).use { server ->
      val gitCli = FakeGitCli(hasChanges = false)

      val outcome =
          activities(gitCli, FakeAgent(), server).attemptIssue(100L, "acme/one", 7, "Fix it")

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
