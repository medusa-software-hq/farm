package software.medusa.farm.cli.command

import software.medusa.farm.cli.api.RunAction
import software.medusa.farm.cli.api.RunAttemptResult
import software.medusa.farm.cli.api.RunEntry
import software.medusa.farm.cli.api.RunResult

/**
 * Shows what a session's runs did, through [emit].
 *
 * Following a session means asking for every run over and over, so this remembers what it has
 * already shown and shows only the rest — otherwise each look would repeat the whole session, and
 * what is new would be the hardest thing to find.
 */
internal class RunLog(private val emit: (String) -> Unit) {
  private val shownEntryCounts = mutableMapOf<Pair<Int, Int>, Int>()
  private val shownStates = mutableMapOf<Pair<Int, Int>, String>()

  fun show(runs: List<RunResult>) {
    runs.forEach { run -> run.attempts.forEach { show(run.ordinal, it) } }
  }

  private fun show(ordinal: Int, attempt: RunAttemptResult) {
    val key = ordinal to attempt.number
    val what = "run $ordinal, try ${attempt.number}"

    if (shownStates.put(key, attempt.state) != attempt.state) emit("$what — ${attempt.state}")

    attempt.entries.drop(shownEntryCounts[key] ?: 0).forEach { emit(render(it)) }
    shownEntryCounts[key] = attempt.entries.size

    // Held back until the state that carries it has been shown, so an outcome never arrives before
    // the try it belongs to is known to have ended.
    attempt.outcome?.let { outcome ->
      emit("  ${outcome.outcome}${outcome.usd?.let { " ($%.2f)".format(it) } ?: ""}")
      outcome.summary.lineSequence().filter { it.isNotBlank() }.forEach { emit("  $it") }
    }
  }

  private fun render(entry: RunEntry): String =
      when (entry) {
        is RunEntry.Warning -> "  ! ${entry.text.trim()}"
        is RunEntry.Step ->
            buildList {
                  entry.text.trim().takeIf { it.isNotEmpty() }?.let { add("  $it") }
                  entry.actions.forEach { add("    · ${render(it)}") }
                }
                .joinToString("\n")
      }

  private fun render(action: RunAction): String =
      when (action) {
        is RunAction.Edited -> "edits ${action.path}"
        is RunAction.Read -> "reads ${action.path}"
        is RunAction.Ran -> "runs ${action.command.oneLine()}"
        is RunAction.Searched -> "searches ${action.query.oneLine()}"
        is RunAction.Used -> action.tool
        RunAction.Unknown -> "(an action of no kind at all)"
      }

  /** A command can be a script; what it starts with is enough to recognise it by. */
  private fun String.oneLine(): String {
    val firstLine = lineSequence().firstOrNull().orEmpty()
    return if (firstLine.length <= COMMAND_WIDTH) firstLine else firstLine.take(COMMAND_WIDTH) + "…"
  }

  private companion object {
    const val COMMAND_WIDTH = 100
  }
}
