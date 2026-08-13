package software.medusa.farm.worker

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import software.medusa.farm.claude.CldAgent
import software.medusa.farm.claude.CldCompletion
import software.medusa.farm.claude.CldMessage
import software.medusa.farm.claude.CldProperSessionStore
import software.medusa.farm.claude.CldRunRequest
import software.medusa.farm.claude.CldRunResult
import software.medusa.farm.github.FakeGitHubServer
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.github.TestAppKey

class AgentActivitiesImplTest {
  private val appKey = TestAppKey()

  /** Records the prompt it was given and streams one assistant message carrying [reply]. */
  private class FakeAgent(private val reply: String) : CldAgent {
    var lastPrompt: String? = null

    override suspend fun run(
        request: CldRunRequest,
        onMessage: (CldMessage) -> Unit,
    ): CldRunResult {
      lastPrompt = request.prompt
      onMessage(CldMessage.Assistant(text = reply, toolActions = emptyList()))
      return CldRunResult(request.session.sessionId, CldCompletion.Ok, cost = null)
    }
  }

  @Test
  fun `fetches the body, prompts with title and body, and returns the agent's text`() {
    FakeGitHubServer { request ->
          val path = request.pathAndQuery.substringBefore('?')
          when {
            path.endsWith("/access_tokens") ->
                FakeGitHubServer.Response(
                    201,
                    """{"token": "tok", "expires_at": "2999-01-01T00:00:00Z"}""",
                )
            path.endsWith("/issues/7") ->
                FakeGitHubServer.Response(200, """{"number": 7, "body": "The widget is broken."}""")
            else -> FakeGitHubServer.Response(404, "unexpected ${request.pathAndQuery}")
          }
        }
        .use { server ->
          val provider =
              GhProperInstallationApiClientProvider(
                  GhProperAppApiClient.build("Iv1.test", appKey.pkcs8Pem, baseUrl = server.baseUrl),
                  baseUrl = server.baseUrl,
              )
          val agent = FakeAgent("The issue reports a broken widget.")
          val store = CldProperSessionStore(Files.createTempDirectory("agent-it"))

          val summary =
              AgentActivitiesImpl(provider, agent, store)
                  .summarizeIssue(100L, "acme/one", 7, "Fix the widget")

          assertEquals("The issue reports a broken widget.", summary)
          assertTrue(agent.lastPrompt!!.contains("Fix the widget"), "prompt lacks the title")
          assertTrue(agent.lastPrompt!!.contains("The widget is broken."), "prompt lacks the body")
        }
  }
}
