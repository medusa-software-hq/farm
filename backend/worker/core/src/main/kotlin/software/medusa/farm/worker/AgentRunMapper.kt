package software.medusa.farm.worker

import software.medusa.farm.claude.CldAssistantStep
import software.medusa.farm.claude.CldRunResult
import software.medusa.farm.claude.CldRunStatus
import software.medusa.farm.claude.CldToolUse
import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

/**
 * Maps a claude session — the steps the assistant took and how it ended — into the backend-neutral
 * [AgentRunLog] and run metadata. This is where Claude's tool vocabulary becomes Farm's semantic
 * actions; a future backend gets its own mapper into the same model.
 */
object AgentRunMapper {
  fun map(steps: List<CldAssistantStep>, result: CldRunResult): MappedAgentRun {
    val log =
        AgentRunLog(
            steps.map { AgentStep(text = it.text, toolActions = it.toolUses.map(::action)) }
        )
    val outcome =
        when (result.status) {
          CldRunStatus.Success -> AgentRunOutcome.SUCCEEDED
          is CldRunStatus.Error -> AgentRunOutcome.ERRORED
        }
    return MappedAgentRun(log = log, outcome = outcome, cost = cost(result))
  }

  private fun cost(result: CldRunResult): AgentRunCost =
      AgentRunCost(
          usd = result.totalCost.usdAmount,
          turns = result.turnCount,
          durationMs = result.sessionDuration.inWholeMilliseconds,
      )

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
