package software.medusa.farm.cli.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import software.medusa.farm.cli.api.RunAction
import software.medusa.farm.cli.api.RunAttemptResult
import software.medusa.farm.cli.api.RunEntry
import software.medusa.farm.cli.api.RunResult

class RunLogTest {
  private val shown = mutableListOf<String>()
  private val log = RunLog { shown.add(it) }

  @Test
  fun `shows what an attempt did`() {
    log.show(
        listOf(run(attempt(entries = listOf(step("Reading the greeter", read("Greeter.java"))))))
    )

    assertTrue(shown.any { it.contains("run 0, try 1") && it.contains("RUNNING") }, "$shown")
    assertTrue(shown.any { it.contains("Reading the greeter") }, "$shown")
    assertTrue(shown.any { it.contains("reads Greeter.java") }, "$shown")
  }

  @Test
  fun `does not say again what it has already said`() {
    val runs = listOf(run(attempt(entries = listOf(step("Reading the greeter")))))
    log.show(runs)
    val afterFirst = shown.size

    log.show(runs)

    assertEquals(afterFirst, shown.size, "the same runs were reported twice: $shown")
  }

  @Test
  fun `shows only what is new when a try goes on`() {
    log.show(listOf(run(attempt(entries = listOf(step("First"))))))
    shown.clear()

    log.show(listOf(run(attempt(entries = listOf(step("First"), step("Second"))))))

    assertTrue(shown.none { it.contains("First") }, "the first step came round again: $shown")
    assertTrue(shown.any { it.contains("Second") }, "$shown")
  }

  @Test
  fun `says when a try changes state`() {
    log.show(listOf(run(attempt(state = "RUNNING", entries = listOf(step("Working"))))))
    shown.clear()

    log.show(listOf(run(attempt(state = "FINISHED", entries = listOf(step("Working"))))))

    assertTrue(shown.any { it.contains("FINISHED") }, "$shown")
  }

  private fun run(vararg attempts: RunAttemptResult) =
      RunResult(ordinal = 0, attempts = attempts.toList())

  private fun attempt(state: String = "RUNNING", entries: List<RunEntry>) =
      RunAttemptResult(number = 1, state = state, entries = entries, outcome = null)

  private fun step(text: String, vararg actions: RunAction) =
      RunEntry.Step(text = text, actions = actions.toList())

  private fun read(path: String) = RunAction.Read(path)
}
