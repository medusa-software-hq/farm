package software.medusa.farm.claude

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * Drives the real driver against fake `claude` binaries — shell scripts that speak the real
 * stream-json format. This is where the contract sad paths live: a stream/exit-code mismatch is
 * trivial to script but awkward to provoke on the real binary. No token, no network — runs every
 * time. Each scenario is one script; the state machine, subprocess mechanics, and argv build are
 * all exercised for real.
 */
class CldScriptedIntegrationTest {
  private val spawner = SysProcessSpawner()

  @Test
  fun `a happy stream yields the steps and a clean result`() = runBlocking {
    val (steps, result) =
        agent(HAPPY).launch(request()).use { it.steps.toList() to it.result.await() }

    assertEquals(1, steps.size)
    assertEquals("working", steps[0].text)
    assertEquals(listOf(CldToolUse("Edit", "a.kt", null, null)), steps[0].toolUses)
    assertEquals(CldCompletion.Ok, result.completion)
    assertEquals(CldRunCost(0.12, 2, 345), result.cost)
  }

  @Test
  fun `an errored result is reported on the result, not thrown`() = runBlocking {
    val result = agent(ERRORED).launch(request()).use { it.result.await() }
    val errored = assertIs<CldCompletion.Errored>(result.completion)
    assertEquals("error_max_budget_usd", errored.subtype)
  }

  @Test
  fun `exit without a result fails the run and is reported`() = runBlocking {
    val reporter = RecordingReporter()
    assertFailsWith<CldIllegalExitException> {
      agent(EXIT_WITHOUT_RESULT, reporter).launch(request()).use { it.result.await() }
    }
    assertNotNull(reporter.exitWithoutResult, "the missing result was not reported")
  }

  @Test
  fun `a message after the result is reported but the run still completes`() = runBlocking {
    val reporter = RecordingReporter()
    val result = agent(MESSAGE_AFTER_RESULT, reporter).launch(request()).use { it.result.await() }
    assertEquals(CldCompletion.Ok, result.completion)
    assertTrue(reporter.afterResult.isNotEmpty(), "the trailing line was not reported")
  }

  @Test
  fun `a stream that does not open with init is reported and fails the run`() = runBlocking {
    val reporter = RecordingReporter()
    assertFailsWith<CldIllegalStartupException> {
      agent(MISSING_INIT, reporter).launch(request()).use { it.result.await() }
    }
    assertTrue(reporter.missingInit, "the missing init was not reported")
  }

  @Test
  fun `a process that lingers after its result completes the run but is reported`() = runBlocking {
    val reporter = RecordingReporter()
    val result = agent(LINGER, reporter).launch(request()).use { it.result.await() }
    assertEquals(CldCompletion.Ok, result.completion)
    assertTrue(reporter.lingered, "the lingering process was not reported")
  }

  @Test
  fun `a non-zero exit after a success result is reported`() = runBlocking {
    val reporter = RecordingReporter()
    val result = agent(EXIT_DISAGREE, reporter).launch(request()).use { it.result.await() }
    assertEquals(CldCompletion.Ok, result.completion)
    assertEquals(3, reporter.exitDisagreed)
  }

  @Test
  fun `a fresh run passes --session-id and a resume passes --resume`() = runBlocking {
    val fresh = argvOf(request(CldSessionSelector.Fresh("sess-7")))
    assertTrue(fresh.containsInOrder("--session-id", "sess-7"))
    assertTrue(fresh.contains("stream-json"))
    assertFalse(fresh.contains("--resume"))

    val ref = CldSessionRef("prior", Files.createTempFile("snap", ".zip"))
    val resumed = argvOf(request(CldSessionSelector.Resume(ref)))
    assertTrue(resumed.containsInOrder("--resume", "prior"))
    assertFalse(resumed.contains("--session-id"))
  }

  /** Runs the argv-recording fake and returns the exact arguments the driver passed it. */
  private suspend fun argvOf(request: CldRunRequest): List<String> {
    val argvFile = Files.createTempFile("argv", ".txt")
    val agent =
        CldProperAgent(
            spawner,
            fakeClaude(ARGV),
            config(mapOf("FAKE_ARGV_OUT" to argvFile.toString())),
            RecordingReporter(),
        )
    agent.launch(request).use { it.result.await() }
    return Files.readAllLines(argvFile)
  }

  private fun agent(script: String, reporter: CldReporter = RecordingReporter()): CldProperAgent =
      CldProperAgent(spawner, fakeClaude(script), config(), reporter)

  private fun config(extraEnv: Map<String, String> = emptyMap()) =
      CldEngineConfig.default(mapOf("PATH" to (System.getenv("PATH") ?: "")) + extraEnv)

  private fun request(session: CldSessionSelector = CldSessionSelector.Fresh("s-1")) =
      CldRunRequest(
          workspace = Files.createTempDirectory("scripted-ws"),
          home = Files.createTempDirectory("scripted-home"),
          prompt = "do it",
          session = session,
      )

  private fun fakeClaude(script: String): SysExecutableHandle {
    val file = Files.createTempFile("fake-claude", ".sh")
    Files.writeString(file, script)
    file.toFile().setExecutable(true)
    return SysExecutableHandle.resolve(file)
  }

  private fun List<String>.containsInOrder(a: String, b: String): Boolean {
    val i = indexOf(a)
    return i >= 0 && i + 1 < size && this[i + 1] == b
  }

  private class RecordingReporter : CldReporter {
    var spawnFailed: Throwable? = null
    var missingInit = false
    var streamFailed: Throwable? = null
    val afterResult = mutableListOf<String>()
    var exitWithoutResult: Pair<Int, String>? = null
    var lingered = false
    var exitDisagreed: Int? = null

    override fun spawnFailed(cause: Throwable) {
      spawnFailed = cause
    }

    override fun missingInit(opening: String?) {
      missingInit = true
    }

    override fun streamFailed(cause: Throwable) {
      streamFailed = cause
    }

    override fun messageAfterResult(line: String) {
      afterResult += line
    }

    override fun exitWithoutResult(exitCode: Int, standardError: String) {
      exitWithoutResult = exitCode to standardError
    }

    override fun lingeredAfterResult() {
      lingered = true
    }

    override fun exitDisagreedWithResult(exitCode: Int) {
      exitDisagreed = exitCode
    }
  }

  private companion object {
    val HAPPY =
        line(
            """{"type":"system","subtype":"init","session_id":"sess-fake","model":"opus","tools":["Read","Edit"]}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"working"},{"type":"tool_use","name":"Edit","input":{"file_path":"a.kt"}}]}}""",
            """{"type":"result","is_error":false,"subtype":"success","total_cost_usd":0.12,"num_turns":2,"duration_ms":345}""",
        )

    val ERRORED =
        line(
            """{"type":"system","subtype":"init","session_id":"s"}""",
            """{"type":"result","is_error":true,"subtype":"error_max_budget_usd"}""",
        )

    val EXIT_WITHOUT_RESULT =
        line(
            """{"type":"system","subtype":"init","session_id":"s"}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}""",
        )

    val MESSAGE_AFTER_RESULT =
        line(
            """{"type":"system","subtype":"init","session_id":"s"}""",
            """{"type":"result","is_error":false,"subtype":"success"}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"late"}]}}""",
        )

    val MISSING_INIT =
        line(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"no init"}]}}""",
            """{"type":"result","is_error":false,"subtype":"success"}""",
        )

    // Emits a clean result, then hangs instead of exiting.
    val LINGER =
        line(
            """{"type":"system","subtype":"init","session_id":"s"}""",
            """{"type":"result","is_error":false,"subtype":"success"}""",
        ) + "sleep 10\n"

    // Emits a success result, then exits non-zero — an exit code at odds with the verdict.
    val EXIT_DISAGREE =
        line(
            """{"type":"system","subtype":"init","session_id":"s"}""",
            """{"type":"result","is_error":false,"subtype":"success"}""",
        ) + "exit 3\n"

    // Records the arguments it was given, then emits a minimal happy stream.
    val ARGV =
        "#!/usr/bin/env bash\n" +
            "printf '%s\\n' \"$@\" > \"\$FAKE_ARGV_OUT\"\n" +
            "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\"}'\n" +
            "echo '{\"type\":\"result\",\"is_error\":false,\"subtype\":\"success\"}'\n"

    /** A fake `claude`: a bash script that `echo`s each canned stream-json line to stdout. */
    private fun line(vararg jsonLines: String): String = buildString {
      append("#!/usr/bin/env bash\n")
      jsonLines.forEach { append("echo '").append(it).append("'\n") }
    }
  }
}
