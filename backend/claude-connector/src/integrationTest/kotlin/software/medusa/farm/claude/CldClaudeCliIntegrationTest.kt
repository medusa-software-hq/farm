package software.medusa.farm.claude

import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * Exercises the real connector classes against the real `claude` CLI to validate the assumptions
 * they bake in about its flag surface, stream-json output, and on-disk session persistence — the
 * things a `FakeCldProcess` cannot catch because it only replays those assumptions back.
 *
 * Kept out of the pure `test` source set; run via the `integrationTest` task. Skips keep it a no-op
 * when unconfigured: the flag-surface check needs only the binary, while the behavioral checks make
 * real, paid calls and additionally need a `CLAUDE_CODE_OAUTH_TOKEN`.
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
    assumeTrue(!token.isNullOrBlank(), "CLAUDE_CODE_OAUTH_TOKEN is not set")
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
  fun `a fresh run streams a parseable session and persists it under HOME`() = runBlocking {
    val claude = claudeOrSkip()
    val token = tokenOrSkip()

    val store = CldProperSessionStore(Files.createTempDirectory("cld-it-store"))
    val sessionId = UUID.randomUUID().toString()
    val home = store.prepare(CldSessionSelector.Fresh(sessionId))

    val agent = CldProperAgent(CldProperProcess(spawner, claude), behavioralConfig(token))

    val messages = mutableListOf<CldMessage>()
    val result =
        agent.run(
            CldRunRequest(
                workspace = Files.createTempDirectory("cld-it-work"),
                home = home,
                prompt = "Reply with exactly the word PONG and nothing else. Do not use any tools.",
                session = CldSessionSelector.Fresh(sessionId),
            )
        ) {
          messages += it
        }

    // The CLI accepted our flags and produced its typed protocol...
    assertTrue(messages.any { it is CldMessage.SystemInit }, "no system init banner")
    assertTrue(messages.any { it is CldMessage.Assistant }, "no assistant turn")
    assertTrue(
        result.completion is CldCompletion.Ok,
        "did not complete cleanly: ${result.completion}",
    )
    assertTrue(result.sessionId.isNotBlank(), "no session id")

    // ...and it persisted the transcript under the HOME we handed it, in the layout the store
    // expects.
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
    val agent = CldProperAgent(CldProperProcess(spawner, claude), behavioralConfig(token))

    // Run 1 on "worker A": plant a codeword, then snapshot.
    val workerA = CldProperSessionStore(Files.createTempDirectory("cld-it-a"))
    val sessionId = UUID.randomUUID().toString()
    val homeA = workerA.prepare(CldSessionSelector.Fresh(sessionId))
    val first =
        agent.run(
            CldRunRequest(
                workspace = Files.createTempDirectory("cld-it-w1"),
                home = homeA,
                prompt = "Remember this codeword for later: MEDUSA. Reply with just: OK.",
                session = CldSessionSelector.Fresh(sessionId),
            )
        ) {}
    val ref = workerA.snapshot(first.sessionId, homeA)

    // Run 2 on "worker B": a different store/HOME, resuming only from the snapshot.
    val workerB = CldProperSessionStore(Files.createTempDirectory("cld-it-b"))
    val homeB = workerB.prepare(CldSessionSelector.Resume(ref))
    val messages = mutableListOf<CldMessage>()
    val second =
        agent.run(
            CldRunRequest(
                workspace = Files.createTempDirectory("cld-it-w2"),
                home = homeB,
                prompt = "What was the codeword I gave you? Reply with just that word.",
                session = CldSessionSelector.Resume(ref),
            )
        ) {
          messages += it
        }

    assertTrue(
        second.completion is CldCompletion.Ok,
        "resume did not complete: ${second.completion}",
    )
    val said = messages.filterIsInstance<CldMessage.Assistant>().joinToString(" ") { it.text }
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
