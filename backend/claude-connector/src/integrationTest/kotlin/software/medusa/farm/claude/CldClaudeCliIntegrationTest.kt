package software.medusa.farm.claude

import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * Exercises the real driver against the real `claude` CLI — its flag surface, stream-json output,
 * and on-disk session persistence — the assumptions the scripted fakes only replay back. Run via
 * the `integrationTest` task; skips keep it a no-op when unconfigured: the flag check needs only
 * the binary, the behavioral checks make real, paid calls and additionally need a
 * CLAUDE_CODE_OAUTH_TOKEN.
 */
class CldClaudeCliIntegrationTest {
  private val spawner = SysProcessSpawner()

  private fun claudeOrSkip(): SysExecutableHandle {
    val handle = runCatching { SysExecutableHandle.locate("claude") }.getOrNull()
    assumeTrue(handle != null, "`claude` is not on PATH")
    return handle!!
  }

  private fun tokenOrSkip(): String {
    val token = System.getenv("CLAUDE_CODE_OAUTH_TOKEN")
    // Only a real Anthropic token (sk-ant-…) runs these paid tests; the provisioned placeholder and
    // any misconfiguration are treated as "not configured", so they skip rather than fail.
    assumeTrue(token != null && token.startsWith("sk-ant-"), "no usable CLAUDE_CODE_OAUTH_TOKEN")
    return token
  }

  @Test
  fun `the CLI still exposes every flag the connector builds`() = runBlocking {
    val claude = claudeOrSkip()

    val help = spawner.spawn(executable = claude, arguments = listOf("--help")).standardOutput

    listOf(
            "-p",
            "--output-format",
            "--session-id",
            "--resume",
            "--setting-sources",
            "--permission-mode",
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
  fun `a fresh run streams parseable steps and persists the session under HOME`() = runBlocking {
    val claude = claudeOrSkip()
    val token = tokenOrSkip()

    val store = CldProperSessionStore(Files.createTempDirectory("cld-it-store"))
    val sessionId = UUID.randomUUID().toString()
    val home = store.prepare(CldSessionSelector.Fresh(sessionId))
    val agent = CldProperAgent(spawner, claude, behavioralConfig(token), CldLoggingReporter())

    val (steps, result) =
        agent
            .launch(
                CldRunRequest(
                    workspace = Files.createTempDirectory("cld-it-work"),
                    home = home,
                    prompt =
                        "Reply with exactly the word PONG and nothing else. Do not use any tools.",
                    session = CldSessionSelector.Fresh(sessionId),
                )
            )
            .use { it.steps.toList() to it.result.await() }

    // The CLI accepted our flags and produced its typed protocol...
    assertTrue(steps.isNotEmpty(), "no assistant steps")
    assertTrue(
        result.completion is CldCompletion.Ok,
        "did not complete cleanly: ${result.completion}",
    )
    assertTrue(result.sessionId.isNotBlank(), "no session id")

    // ...and persisted the transcript under the HOME we handed it, in the layout the store expects.
    val projects = home.resolve(".claude/projects")
    assertTrue(projects.exists(), "no .claude/projects under HOME")
    val transcripts = projects.listDirectoryEntries().flatMap { it.listDirectoryEntries("*.jsonl") }
    assertTrue(transcripts.isNotEmpty(), "no session transcript persisted")

    val ref = store.snapshot(result.sessionId, home)
    assertTrue(Files.size(ref.snapshot) > 0, "empty snapshot")
  }

  @Test
  fun `a snapshotted session resumes with its context on another HOME`() = runBlocking {
    val claude = claudeOrSkip()
    val token = tokenOrSkip()
    val agent = CldProperAgent(spawner, claude, behavioralConfig(token), CldLoggingReporter())

    // The workspace path is fixed across both runs: claude files a session's transcript under a
    // slug
    // derived from the working directory, so --resume only finds it when run 2 shares run 1's path
    // (the worker pins this path for the same reason). Only the HOME/store differs — a second
    // worker.
    val workspace = Files.createTempDirectory("cld-it-resume-ws")

    // Run 1 on "worker A": plant a codeword, then snapshot.
    val workerA = CldProperSessionStore(Files.createTempDirectory("cld-it-a"))
    val sessionId = UUID.randomUUID().toString()
    val homeA = workerA.prepare(CldSessionSelector.Fresh(sessionId))
    val first =
        agent
            .launch(
                CldRunRequest(
                    workspace = workspace,
                    home = homeA,
                    prompt = "Remember this codeword for later: MEDUSA. Reply with just: OK.",
                    session = CldSessionSelector.Fresh(sessionId),
                )
            )
            .use { it.result.await() }
    val ref = workerA.snapshot(first.sessionId, homeA)

    // Run 2 on "worker B": a different store/HOME, resuming only from the snapshot.
    val workerB = CldProperSessionStore(Files.createTempDirectory("cld-it-b"))
    val homeB = workerB.prepare(CldSessionSelector.Resume(ref))
    val steps =
        agent
            .launch(
                CldRunRequest(
                    workspace = workspace,
                    home = homeB,
                    prompt = "What was the codeword I gave you? Reply with just that word.",
                    session = CldSessionSelector.Resume(ref),
                )
            )
            .use {
              val collected = it.steps.toList()
              assertTrue(
                  it.result.await().completion is CldCompletion.Ok,
                  "resume did not complete",
              )
              collected
            }

    val said = steps.joinToString(" ") { it.text }
    assertTrue(
        said.contains("MEDUSA", ignoreCase = true),
        "resumed session lost prior context: '$said'",
    )
  }

  private fun behavioralConfig(token: String): CldEngineConfig =
      CldEngineConfig.default(
              environment =
                  mapOf(
                      "PATH" to (System.getenv("PATH") ?: ""),
                      "CLAUDE_CODE_OAUTH_TOKEN" to token,
                  )
          )
          // A tight budget and timeout: these are trivial prompts, and a runaway must not burn
          // money.
          .copy(maxBudgetUsd = 0.50, wallClockTimeout = 3.minutes)
}
