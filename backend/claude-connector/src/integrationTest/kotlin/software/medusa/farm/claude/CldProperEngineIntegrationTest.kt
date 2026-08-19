package software.medusa.farm.claude

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * Drives the engine against fake `claude` binaries — shell scripts speaking the real protocol. This
 * is where the sad paths live: an engine that misbehaves is trivial to script and awkward to
 * provoke on the real thing. No token, no network — runs every time.
 */
class CldProperEngineIntegrationTest {
  private val processSpawner = SysProcessSpawner()

  @Test
  fun `a well-behaved session yields its steps and a successful result`(): Unit = runBlocking {
    val (steps, result) =
        engine(HAPPY).runSession(config(), PROMPT) { collectSteps() to awaitResult() }

    assertEquals(1, steps.size)
    assertEquals("working", steps.single().text)
    assertEquals(listOf(CldToolUse("Edit", "a.kt", null, null)), steps.single().toolUses)
    assertEquals(CldRunStatus.Success, result.status)
    assertEquals(CldCost(usdAmount = 0.12), result.totalCost)
    assertEquals(2, result.turnCount)
    assertEquals(345.milliseconds, result.sessionDuration)
  }

  @Test
  fun `the greeting describes the session`(): Unit = runBlocking {
    val info = engine(HAPPY).runSession(config(), PROMPT) { info }

    assertEquals(CldSessionId("sess-fake"), info.sessionId)
    assertEquals(CldModelId("claude-opus-5"), info.modelId)
    assertEquals(
        setOf(CldToolRule.Read, CldToolRule.Edit, CldToolRule.Unrecognized("TodoWrite")),
        info.availableToolSpecifiers,
    )
  }

  @Test
  fun `an assistant-reported failure is returned, not raised`(): Unit = runBlocking {
    val result = engine(SPEND_BUDGET_REACHED).runSession(config(), PROMPT) { awaitResult() }

    assertEquals(CldRunStatus.Error.SpendBudgetReached, result.status)
  }

  @Test
  fun `a stop this library does not know by name is still reported as an error`(): Unit =
      runBlocking {
        val result = engine(UNKNOWN_ERROR).runSession(config(), PROMPT) { awaitResult() }

        val error = assertIs<CldRunStatus.Error.Unrecognized>(result.status)
        assertEquals("error_something_new", error.type)
      }

  @Test
  fun `a session that never reports a result fails and is reported`(): Unit = runBlocking {
    val reporter = RecordingAnomalyReporter()

    assertFailsWith<CldAbnormalExitError> {
      engine(NO_RESULT, reporter).runSession(config(), PROMPT) { awaitResult() }
    }

    assertNotNull(reporter.exitWithoutResult, "the missing result was not reported")
  }

  @Test
  fun `a session whose first word is not the greeting fails and is reported`(): Unit = runBlocking {
    val reporter = RecordingAnomalyReporter()

    assertFailsWith<CldAbnormalStartError> {
      engine(NO_GREETING, reporter).runSession(config(), PROMPT) { awaitResult() }
    }

    assertNotNull(reporter.missingInitLine, "the missing greeting was not reported")
  }

  @Test
  fun `a session that says nothing at all fails and is reported`(): Unit = runBlocking {
    val reporter = RecordingAnomalyReporter()

    assertFailsWith<CldAbnormalStartError> {
      engine(SILENT, reporter).runSession(config(), PROMPT) { awaitResult() }
    }

    assertTrue(reporter.reportedMissingInit, "the silence was not reported")
  }

  @Test
  fun `sub-cent spending is kept, not rounded away`(): Unit = runBlocking {
    val result = engine(TINY_COST).runSession(config(), PROMPT) { awaitResult() }

    assertEquals(CldCost(usdAmount = 0.0004), result.totalCost)
  }

  @Test
  fun `an unintelligible line fails the session and is reported`(): Unit = runBlocking {
    val reporter = RecordingAnomalyReporter()

    assertFailsWith<CldAbnormalRunError> {
      engine(UNINTELLIGIBLE, reporter).runSession(config(), PROMPT) { awaitResult() }
    }

    assertNotNull(reporter.unexpectedProgressLine, "the unintelligible line was not reported")
  }

  @Test
  fun `messages this library does not act on are passed over`(): Unit = runBlocking {
    val reporter = RecordingAnomalyReporter()

    val result =
        engine(UNACTED_ON_MESSAGES, reporter).runSession(config(), PROMPT) { awaitResult() }

    assertEquals(CldRunStatus.Success, result.status)
    assertNull(reporter.unexpectedProgressLine, "an unmodelled message was treated as a fault")
  }

  @Test
  fun `talking after the result fails the session and is reported`(): Unit = runBlocking {
    val reporter = RecordingAnomalyReporter()

    assertFailsWith<CldAbnormalExitError> {
      engine(OUTPUT_AFTER_RESULT, reporter).runSession(config(), PROMPT) { awaitResult() }
    }

    assertNotNull(reporter.outputAfterResult, "the trailing line was not reported")
  }

  @Test
  fun `an engine that will not end after its result fails the session`(): Unit = runBlocking {
    val reporter = RecordingAnomalyReporter()

    assertFailsWith<CldAbnormalExitError> {
      engine(LINGERING, reporter).runSession(config(), PROMPT) { awaitResult() }
    }

    assertTrue(
        reporter.reportedLingered || reporter.reportedHangOutput,
        "neither the lingering nor the silence was reported",
    )
  }

  @Test
  fun `success claimed with a failing exit fails the session and is reported`(): Unit =
      runBlocking {
        val reporter = RecordingAnomalyReporter()

        assertFailsWith<CldAbnormalExitError> {
          engine(SUCCESS_THEN_FAILING_EXIT, reporter).runSession(config(), PROMPT) { awaitResult() }
        }

        assertEquals(3, reporter.unexpectedNonZeroExitCode)
      }

  @Test
  fun `failure claimed with a clean exit fails the session and is reported`(): Unit = runBlocking {
    val reporter = RecordingAnomalyReporter()

    assertFailsWith<CldAbnormalExitError> {
      engine(FAILURE_THEN_CLEAN_EXIT, reporter).runSession(config(), PROMPT) { awaitResult() }
    }

    assertTrue(reporter.reportedUnexpectedZeroExitCode, "the disagreeing exit was not reported")
  }

  @Test
  fun `waiting only for the result never stalls a session that is streaming steps`(): Unit =
      runBlocking {
        // Nothing reads the step channel here. The engine must still be read to its end, or it
        // would
        // block writing steps nobody is collecting and never reach its result.
        val result = engine(MANY_STEPS).runSession(config(), PROMPT) { awaitResult() }

        assertEquals(CldRunStatus.Success, result.status)
      }

  @Test
  fun `leaving the block early ends an engine that would otherwise linger`(): Unit = runBlocking {
    // The fake sleeps well past this bound after its result. Leaving the block has to end it rather
    // than wait for it.
    val elapsed = measureTime { engine(LINGERING).runSession(config(), PROMPT) { info.sessionId } }

    assertTrue(elapsed < 5.seconds, "leaving the block waited for the lingering engine: $elapsed")
  }

  @Test
  fun `an operational fault fails the session even when the block never waits`(): Unit =
      runBlocking {
        // The block only sits there; nothing asks how the session went. It must still fail.
        assertFailsWith<CldAbnormalExitError> {
          engine(NO_RESULT).runSession(config(), PROMPT) { awaitCancellation() }
        }
      }

  @Test
  fun `the session sees only what it needs of the environment`(): Unit = runBlocking {
    val workspacePath = Files.createTempDirectory("engine-env")

    engine(ENV).runSession(config(workspacePath), PROMPT) { awaitResult() }

    // The assistant can run commands and read its own environment, and what it reads ends up in a
    // transcript we store and send on to be summarized. Anything else we hold would leak through.
    val seen =
        Files.readAllLines(workspacePath.resolve(ENV_FILE_NAME))
            .map { it.substringBefore('=') }
            .toSet() - setOf("PWD", "SHLVL", "_")
    assertEquals(
        setOf("PATH", "HOME", "CLAUDE_CONFIG_DIR", "CLAUDE_CODE_OAUTH_TOKEN"),
        seen,
    )
  }

  @Test
  fun `the session config reaches the engine`(): Unit = runBlocking {
    val workspacePath = Files.createTempDirectory("engine-argv")
    val sessionConfig =
        config(workspacePath)
            .copy(
                permissionMode = CldPermissionMode.BypassPermissions,
                settingSources = listOf(CldSettingSource.User, CldSettingSource.Local),
                allowedToolRules =
                    listOf(
                        CldToolRule.Read,
                        CldToolRule.Bash(CldToolRule.Bash.CommandMask("git:*")),
                    ),
                disallowedToolRules = listOf(CldToolRule.WebFetch, CldToolRule.AskUserQuestion),
                systemPromptSuffix = "be autonomous",
                spendBudget = CldCost(usdAmount = 2.5),
            )

    engine(ARGV).runSession(sessionConfig, PROMPT) { awaitResult() }

    val argv = Files.readAllLines(workspacePath.resolve(ARGV_FILE_NAME))
    assertContains(argv, PROMPT)
    assertTrue(argv.containsInOrder("--permission-mode", "bypassPermissions"))
    assertTrue(argv.containsInOrder("--setting-sources", "user,local"))
    assertTrue(argv.containsInOrder("--allowedTools", "Read Bash(git:*)"))
    assertTrue(argv.containsInOrder("--disallowedTools", "WebFetch AskUserQuestion"))
    assertTrue(argv.containsInOrder("--max-budget-usd", "2.5"))
    assertTrue(argv.containsInOrder("--append-system-prompt", "be autonomous"))
  }

  private suspend fun CldSessionScope.collectSteps(): List<CldAssistantStep> {
    val steps = mutableListOf<CldAssistantStep>()
    for (step in assistantStepChannel) steps += step
    return steps
  }

  private fun engine(
      script: String,
      anomalyReporter: CldAnomalyReporter = RecordingAnomalyReporter(),
  ): CldProperEngine =
      CldProperEngine(
          processSpawner = processSpawner,
          claudeExecutableHandle = fakeClaude(script),
          systemEnvMap = SYSTEM_ENV,
          authToken = CldAuthToken(AUTH_TOKEN),
          anomalyReporter = anomalyReporter,
      )

  private fun config(
      workspacePath: Path = Files.createTempDirectory("engine-workspace")
  ): CldSessionConfig =
      CldSessionConfig(
          workspacePath = workspacePath,
          configDirPath = Files.createTempDirectory("engine-config"),
          permissionMode = CldPermissionMode.AcceptEdits,
          settingSources = listOf(CldSettingSource.Project),
          allowedToolRules = listOf(CldToolRule.Read, CldToolRule.Edit),
          disallowedToolRules = listOf(CldToolRule.WebFetch),
          systemPromptSuffix = "",
          spendBudget = CldCost(usdAmount = 10.0),
      )

  private fun fakeClaude(script: String): SysExecutableHandle {
    val file = Files.createTempFile("fake-claude", ".sh")
    Files.writeString(file, script)
    file.toFile().setExecutable(true)
    return SysExecutableHandle.resolve(file)
  }

  private fun List<String>.containsInOrder(first: String, second: String): Boolean {
    val index = indexOf(first)
    return index >= 0 && index + 1 < size && this[index + 1] == second
  }

  private class RecordingAnomalyReporter : CldAnomalyReporter {
    var spawnFailure: Throwable? = null
    var reportedMissingInit: Boolean = false
    var missingInitLine: String? = null
    var unexpectedProgressLine: String? = null
    var outputAfterResult: String? = null
    var exitWithoutResult: Int? = null
    var reportedHangOutput: Boolean = false
    var reportedLingered: Boolean = false
    var unexpectedNonZeroExitCode: Int? = null
    var reportedUnexpectedZeroExitCode: Boolean = false

    override fun reportSpawnFailed(cause: Throwable) {
      spawnFailure = cause
    }

    override fun reportMissingInitMessage(firstLine: String?) {
      reportedMissingInit = true
      missingInitLine = firstLine
    }

    override fun reportUnexpectedProgressLine(progressLine: String) {
      unexpectedProgressLine = progressLine
    }

    override fun reportOutputAfterResult(outputLine: String) {
      outputAfterResult = outputLine
    }

    override fun reportExitWithoutResult(exitCode: Int) {
      exitWithoutResult = exitCode
    }

    override fun reportHangOutput() {
      reportedHangOutput = true
    }

    override fun reportLingeredAfterResult() {
      reportedLingered = true
    }

    override fun reportUnexpectedNonZeroExitCode(exitCode: Int, runResult: CldRunResult) {
      unexpectedNonZeroExitCode = exitCode
    }

    override fun reportUnexpectedZeroExitCode(runResult: CldRunResult) {
      reportedUnexpectedZeroExitCode = true
    }
  }

  private companion object {
    const val PROMPT = "do it"

    const val ARGV_FILE_NAME = "argv.txt"

    const val ENV_FILE_NAME = "env.txt"

    const val AUTH_TOKEN = "sk-ant-oat01-fake"

    // The fakes are bash scripts, so they need a real PATH and a real home to run at all.
    val SYSTEM_ENV =
        CldSystemEnvMap(
            path = System.getenv("PATH") ?: "",
            home = System.getenv("HOME") ?: "",
        )

    const val GREETING =
        """{"type":"system","subtype":"init","session_id":"sess-fake",""" +
            """"model":"claude-opus-5","tools":["Read","Edit","TodoWrite"]}"""

    const val ASSISTANT_STEP =
        """{"type":"assistant","message":{"content":[{"type":"text","text":"working"},""" +
            """{"type":"tool_use","name":"Edit","input":{"file_path":"a.kt"}}]}}"""

    const val SUCCESS_RESULT =
        """{"type":"result","is_error":false,"subtype":"success","total_cost_usd":0.12,""" +
            """"num_turns":2,"duration_ms":345}"""

    val HAPPY = script(GREETING, ASSISTANT_STEP, SUCCESS_RESULT)

    val SPEND_BUDGET_REACHED =
        script(
            GREETING,
            """{"type":"result","is_error":true,"subtype":"error_max_budget_usd"}""",
        ) + "exit 1\n"

    val UNKNOWN_ERROR =
        script(
            GREETING,
            """{"type":"result","is_error":true,"subtype":"error_something_new"}""",
        ) + "exit 1\n"

    val NO_RESULT = script(GREETING, ASSISTANT_STEP)

    val TINY_COST =
        script(
            GREETING,
            """{"type":"result","is_error":false,"subtype":"success","total_cost_usd":0.0004}""",
        )

    val NO_GREETING = script(ASSISTANT_STEP, SUCCESS_RESULT)

    // Starts, says nothing, ends.
    val SILENT = "#!/usr/bin/env bash\n"

    val UNINTELLIGIBLE = script(GREETING, "this is not the protocol", SUCCESS_RESULT)

    // A tool result and a mid-session notice: on the protocol, but not things this library acts on.
    val UNACTED_ON_MESSAGES =
        script(
            GREETING,
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}""",
            """{"type":"system","subtype":"status","message":"still going"}""",
            ASSISTANT_STEP,
            SUCCESS_RESULT,
        )

    val OUTPUT_AFTER_RESULT = script(GREETING, SUCCESS_RESULT, ASSISTANT_STEP)

    // Reports a clean result, then refuses to end.
    val LINGERING = script(GREETING, SUCCESS_RESULT) + "sleep 10\n"

    val SUCCESS_THEN_FAILING_EXIT = script(GREETING, SUCCESS_RESULT) + "exit 3\n"

    val FAILURE_THEN_CLEAN_EXIT =
        script(
            GREETING,
            """{"type":"result","is_error":true,"subtype":"error_during_execution"}""",
        )

    // Enough steps that a step channel nobody drains would certainly have stalled the engine.
    val MANY_STEPS =
        "#!/usr/bin/env bash\n" +
            "echo '$GREETING'\n" +
            "for i in $(seq 1 50); do echo '$ASSISTANT_STEP'; done\n" +
            "echo '$SUCCESS_RESULT'\n"

    // Records the environment it was given into its working directory, then behaves.
    val ENV =
        "#!/usr/bin/env bash\n" +
            "env > $ENV_FILE_NAME\n" +
            "echo '$GREETING'\n" +
            "echo '$SUCCESS_RESULT'\n"

    // Records the arguments it was given into its working directory, then behaves.
    val ARGV =
        "#!/usr/bin/env bash\n" +
            "printf '%s\\n' \"$@\" > $ARGV_FILE_NAME\n" +
            "echo '$GREETING'\n" +
            "echo '$SUCCESS_RESULT'\n"

    /** A fake `claude`: a bash script echoing each canned protocol line to standard output. */
    private fun script(vararg jsonLines: String): String = buildString {
      append("#!/usr/bin/env bash\n")
      jsonLines.forEach { append("echo '").append(it).append("'\n") }
    }
  }
}
