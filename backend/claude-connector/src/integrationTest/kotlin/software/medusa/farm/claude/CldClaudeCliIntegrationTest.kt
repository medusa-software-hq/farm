package software.medusa.farm.claude

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * Exercises the engine against the real `claude` CLI — its flag surface, its protocol, and where it
 * keeps a session's state — the assumptions the scripted fakes only replay back.
 *
 * Run via the `integrationTest` task, so it runs only when asked for; the CLI and a real token are
 * then required, never skipped.
 */
class CldClaudeCliIntegrationTest {
  private val processSpawner = SysProcessSpawner()

  // The connector bounds no wall-clock time — that is a use-site concern, and here the use site is
  // this test. A trip cancels the coroutine, which leaves the block and kills the process tree.
  private val sessionBudget = 3.minutes

  @Test
  fun `the CLI still exposes every flag the engine builds`(): Unit = runBlocking {
    val help =
        processSpawner
            .spawn(
                executable = claude(),
                arguments = listOf("--help"),
                environment = System.getenv(),
            )
            .standardOutput

    listOf(
            "-p",
            "--output-format",
            "--verbose",
            "--permission-mode",
            "--setting-sources",
            "--allowedTools",
            "--disallowedTools",
            "--append-system-prompt",
            "--max-budget-usd",
        )
        .forEach { flag ->
          assertTrue(help.contains(flag), "claude --help no longer documents $flag")
        }
  }

  @Test
  fun `a session streams parseable steps and keeps its state where it was told to`(): Unit =
      runBlocking {
        val configDirPath = Files.createTempDirectory("cld-it-config")
        val workspacePath = Files.createTempDirectory("cld-it-work")

        val (steps, result) =
            withTimeout(sessionBudget) {
              engine().runSession(
                  config = config(workspacePath, configDirPath),
                  prompt =
                      "Reply with exactly the word PONG and nothing else. Do not use any tools.",
              ) {
                val collected = mutableListOf<CldAssistantStep>()
                for (step in assistantStepChannel) collected += step
                collected to awaitResult()
              }
            }

        assertTrue(steps.isNotEmpty(), "no assistant steps")
        assertTrue(
            result.status is CldRunStatus.Success,
            "did not finish cleanly: ${result.status}",
        )

        // The transcript landed under the directory we named, not the operator's own.
        val projects = configDirPath.resolve("projects")
        assertTrue(projects.exists(), "no projects directory under the session's config directory")
        val transcripts =
            projects.listDirectoryEntries().flatMap { it.listDirectoryEntries("*.jsonl") }
        assertTrue(transcripts.isNotEmpty(), "no session transcript persisted")
      }

  private fun engine(): CldProperEngine =
      CldProperEngine(
          processSpawner = processSpawner,
          claudeExecutableHandle = claude(),
          systemEnvMap =
              CldSystemEnvMap(
                  path = System.getenv("PATH") ?: error("PATH is required"),
                  home = System.getenv("HOME") ?: error("HOME is required"),
              ),
          authToken = CldAuthToken(oauthToken()),
          anomalyReporter = LoggingAnomalyReporter(),
      )

  private fun config(workspacePath: java.nio.file.Path, configDirPath: java.nio.file.Path) =
      CldSessionConfig(
          workspacePath = workspacePath,
          configDirPath = configDirPath,
          permissionMode = CldPermissionMode.AcceptEdits,
          settingSources = listOf(CldSettingSource.Project),
          allowedToolRules = emptyList(),
          disallowedToolRules = emptyList(),
          systemPromptSuffix = "",
          // A tight cap: this is a trivial prompt, and a runaway must not burn money.
          spendBudget = CldCost(usdAmount = 0.50),
      )

  private fun claude(): SysExecutableHandle =
      runCatching { SysExecutableHandle.locate("claude") }.getOrNull()
          ?: error("`claude` is not on PATH")

  private fun oauthToken(): String {
    val token = System.getenv("CLAUDE_CODE_OAUTH_TOKEN")
    check(!token.isNullOrBlank()) { "CLAUDE_CODE_OAUTH_TOKEN is not set" }
    check(token.startsWith("sk-ant-")) { "CLAUDE_CODE_OAUTH_TOKEN is not a real token" }
    return token
  }

  /**
   * Puts every anomaly where a failed run can be read back from, since the only account of what the
   * real thing did is the one taken while it was running.
   */
  private class LoggingAnomalyReporter : CldAnomalyReporter {
    override fun reportSpawnFailed(cause: Throwable) = log("could not be started: $cause")

    override fun reportMissingInitMessage(firstLine: String?) =
        log("said <$firstLine> instead of a greeting")

    override fun reportUnexpectedProgressLine(progressLine: String) =
        log("said <$progressLine>, which is not the protocol")

    override fun reportOutputAfterResult(outputLine: String) =
        log("said <$outputLine> after its result")

    override fun reportExitWithoutResult(exitCode: Int) = log("ended ($exitCode) without a result")

    override fun reportHangOutput() = log("neither said more nor fell silent after its result")

    override fun reportLingeredAfterResult() = log("would not end after its result")

    override fun reportUnexpectedNonZeroExitCode(exitCode: Int, runResult: CldRunResult) =
        log("claimed ${runResult.status} and then ended ($exitCode)")

    override fun reportUnexpectedZeroExitCode(runResult: CldRunResult) =
        log("claimed ${runResult.status} and then ended cleanly")

    private fun log(message: String) = System.err.println("[claude] the session $message")
  }
}
