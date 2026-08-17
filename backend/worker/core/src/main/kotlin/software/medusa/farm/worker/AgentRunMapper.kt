package software.medusa.farm.worker

import software.medusa.farm.claude.CldCompletion
import software.medusa.farm.claude.CldRunCost
import software.medusa.farm.claude.CldRunResult
import software.medusa.farm.claude.CldStep
import software.medusa.farm.claude.CldToolUse
import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

/**
 * Maps a claude run — its streamed [CldStep]s and terminal [CldRunResult] — into the
 * backend-neutral [AgentRunLog] and run metadata. This is where Claude's tool vocabulary becomes
 * Farm's semantic actions; a future backend gets its own mapper into the same model.
 */
object AgentRunMapper {
  fun map(steps: List<CldStep>, result: CldRunResult): MappedAgentRun {
    val log =
        AgentRunLog(
            steps.map { AgentStep(text = it.text, toolActions = it.toolUses.map(::action)) }
        )
    val outcome =
        if (result.completion is CldCompletion.Errored) AgentRunOutcome.ERRORED
        else AgentRunOutcome.SUCCEEDED
    return MappedAgentRun(log = log, outcome = outcome, cost = cost(result.cost))
  }

  private fun cost(cost: CldRunCost?): AgentRunCost? {
    val usd = cost?.totalCostUsd ?: return null
    val turns = cost.numTurns ?: return null
    val durationMs = cost.durationMs ?: return null
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
