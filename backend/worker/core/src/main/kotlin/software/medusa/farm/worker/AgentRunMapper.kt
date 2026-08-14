package software.medusa.farm.worker

import software.medusa.farm.claude.CldMessage
import software.medusa.farm.claude.CldToolUse
import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

/**
 * Maps a claude run's [CldMessage] stream into the backend-neutral [AgentRunLog] and run metadata.
 * This is where Claude's tool vocabulary becomes Farm's semantic actions; a future backend gets its
 * own mapper into the same model.
 */
object AgentRunMapper {
  fun map(messages: List<CldMessage>): MappedAgentRun {
    val steps =
        messages.filterIsInstance<CldMessage.Assistant>().map { assistant ->
          AgentStep(text = assistant.text, toolActions = assistant.toolUses.map(::action))
        }
    val result = messages.filterIsInstance<CldMessage.Result>().lastOrNull()
    val outcome =
        if (result?.isError == true) AgentRunOutcome.ERRORED else AgentRunOutcome.SUCCEEDED
    return MappedAgentRun(log = AgentRunLog(steps), outcome = outcome, cost = result?.let(::cost))
  }

  private fun cost(result: CldMessage.Result): AgentRunCost? {
    val usd = result.totalCostUsd ?: return null
    val turns = result.numTurns ?: return null
    val durationMs = result.durationMs ?: return null
    return AgentRunCost(usd = usd, turns = turns, durationMs = durationMs)
  }

  private fun action(use: CldToolUse): AgentToolAction =
      when (use.name) {
        "Edit",
        "MultiEdit",
        "Write",
        "NotebookEdit" -> use.filePath.toAction(use, AgentToolAction::EditFile)
        "Read" -> use.filePath.toAction(use, AgentToolAction::ReadFile)
        "Bash" -> use.command.toAction(use, AgentToolAction::RunCommand)
        "Glob",
        "Grep" -> use.pattern.toAction(use, AgentToolAction::Search)
        else -> AgentToolAction.Other(use.name)
      }

  /** Applies [build] when the relevant wire field was present; falls back to [Other] otherwise. */
  private fun String?.toAction(
      use: CldToolUse,
      build: (String) -> AgentToolAction,
  ): AgentToolAction = this?.let(build) ?: AgentToolAction.Other(use.name)
}
