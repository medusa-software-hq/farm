package software.medusa.farm.claude

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CldProperAgentTest {
  private val workspace = Path.of("/work/repo")
  private val home = Path.of("/run/home")

  private fun config(): CldEngineConfig =
      CldEngineConfig.default(
          environment = mapOf("PATH" to "/usr/bin", "CLAUDE_CODE_OAUTH_TOKEN" to "t")
      )

  private fun freshRequest(prompt: String, sessionId: String): CldRunRequest =
      CldRunRequest(
          workspace = workspace,
          home = home,
          prompt = prompt,
          session = CldSessionSelector.Fresh(sessionId),
      )

  private fun valueAfter(args: List<String>, flag: String): String? =
      args.indexOf(flag).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

  @Test
  fun `a fresh run builds the stream-json invocation and reports the result`() = runBlocking {
    val process =
        FakeCldProcess.of(
            listOf(
                CldMessage.SystemInit(sessionId = "sess-1", model = "opus", tools = listOf("Read")),
                CldMessage.Assistant(
                    text = "working",
                    toolUses =
                        listOf(
                            CldToolUse(
                                name = "Edit",
                                filePath = "a",
                                command = null,
                                pattern = null,
                            )
                        ),
                ),
                CldMessage.Result(
                    isError = false,
                    subtype = "success",
                    totalCostUsd = 0.4,
                    numTurns = 3,
                    durationMs = 900,
                ),
            )
        )
    val agent = CldProperAgent(process, config())

    val received = mutableListOf<CldMessage>()
    val result = agent.run(freshRequest("solve #7", "req-id")) { received += it }

    val args = process.lastInvocation!!.arguments
    assertEquals("solve #7", valueAfter(args, "-p"))
    assertEquals("stream-json", valueAfter(args, "--output-format"))
    assertTrue(args.contains("--verbose"))
    assertEquals("project", valueAfter(args, "--setting-sources"))
    assertEquals("acceptEdits", valueAfter(args, "--permission-mode"))
    assertEquals("req-id", valueAfter(args, "--session-id"))
    assertTrue(args.none { it == "--resume" })

    // The whole stream reaches the collector, in order.
    assertEquals(3, received.size)
    // The session id comes from the init banner; the verdict and cost from the result.
    assertEquals("sess-1", result.sessionId)
    assertEquals(CldCompletion.Ok, result.completion)
    assertEquals(CldRunCost(0.4, 3, 900), result.cost)
    // HOME is overlaid so the session persists under the caller's directory.
    assertEquals(home.toString(), process.lastInvocation!!.environment["HOME"])
    assertEquals(workspace, process.lastInvocation!!.workingDirectory)
    assertTrue(process.closed)
  }

  @Test
  fun `tool policy is rendered as space-separated allow and deny lists`() = runBlocking {
    val process = FakeCldProcess.of(listOf(okResult()))
    CldProperAgent(process, config()).run(freshRequest("x", "s")) {}

    val args = process.lastInvocation!!.arguments
    assertEquals("Read Edit Write Bash Glob Grep Task", valueAfter(args, "--allowedTools"))
    assertTrue(valueAfter(args, "--disallowedTools")!!.contains("Bash(git push:*)"))
  }

  @Test
  fun `a resume run passes --resume and not --session-id`() = runBlocking {
    val process = FakeCldProcess.of(listOf(okResult()))
    val request =
        CldRunRequest(
            workspace = workspace,
            home = home,
            prompt = "address review",
            session = CldSessionSelector.Resume(CldSessionRef("prior", Path.of("/snap.zip"))),
        )

    CldProperAgent(process, config()).run(request) {}

    val args = process.lastInvocation!!.arguments
    assertEquals("prior", valueAfter(args, "--resume"))
    assertTrue(args.none { it == "--session-id" })
  }

  @Test
  fun `an errored result is reported, not thrown`() = runBlocking {
    val process =
        FakeCldProcess.of(
            listOf(
                CldMessage.Result(
                    isError = true,
                    subtype = "error_max_budget_usd",
                    totalCostUsd = 10.0,
                    numTurns = 20,
                    durationMs = 5000,
                )
            )
        )

    val result = CldProperAgent(process, config()).run(freshRequest("x", "s")) {}

    val errored = assertIs<CldCompletion.Errored>(result.completion)
    assertEquals("error_max_budget_usd", errored.subtype)
  }

  @Test
  fun `a run that ends with no result is an operational failure`() {
    val process = FakeCldProcess.of(cannedMessages = emptyList())
    assertFailsWith<CldConnectorException> {
      runBlocking { CldProperAgent(process, config()).run(freshRequest("x", "s")) {} }
    }
    assertTrue(process.closed)
  }

  @Test
  fun `an omitted model and budget drop their flags`() = runBlocking {
    val leanConfig = config().copy(model = null, maxBudgetUsd = null)
    val process = FakeCldProcess.of(listOf(okResult()))

    CldProperAgent(process, leanConfig).run(freshRequest("x", "s")) {}

    val args = process.lastInvocation!!.arguments
    assertTrue(args.none { it == "--model" })
    assertTrue(args.none { it == "--max-budget-usd" })
  }

  private fun okResult(): CldMessage.Result =
      CldMessage.Result(
          isError = false,
          subtype = "success",
          totalCostUsd = null,
          numTurns = null,
          durationMs = null,
      )
}
