package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.farm.claude.CldMessage
import software.medusa.farm.claude.CldToolUse
import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

class AgentRunMapperTest {
  @Test
  fun `classifies each tool use into its semantic action`() {
    val assistant =
        CldMessage.Assistant(
            text = "working",
            toolUses =
                listOf(
                    use("Edit", filePath = "src/A.kt"),
                    use("Write", filePath = "src/B.kt"),
                    use("Read", filePath = "src/C.kt"),
                    use("Bash", command = "gradle test"),
                    use("Grep", pattern = "TODO"),
                    use("Sorcery"),
                ),
        )

    val step = AgentRunMapper.map(listOf(assistant)).log.steps.single()
    assertEquals("working", step.text)
    assertEquals(
        listOf(
            AgentToolAction.EditFile("src/A.kt"),
            AgentToolAction.EditFile("src/B.kt"),
            AgentToolAction.ReadFile("src/C.kt"),
            AgentToolAction.RunCommand("gradle test"),
            AgentToolAction.Search("TODO"),
            AgentToolAction.Other("Sorcery"),
        ),
        step.toolActions,
    )
  }

  @Test
  fun `a tool use missing its wire field degrades to Other`() {
    val assistant = CldMessage.Assistant(text = "", toolUses = listOf(use("Edit")))
    assertEquals(
        listOf<AgentToolAction>(AgentToolAction.Other("Edit")),
        AgentRunMapper.map(listOf(assistant)).log.steps.single().toolActions,
    )
  }

  @Test
  fun `maps the result to outcome and cost`() {
    val ok =
        AgentRunMapper.map(
            listOf(result(isError = false, cost = 0.42, turns = 3, durationMs = 1200))
        )
    assertEquals(AgentRunOutcome.SUCCEEDED, ok.outcome)
    assertEquals(AgentRunCost(usd = 0.42, turns = 3, durationMs = 1200), ok.cost)

    val errored = AgentRunMapper.map(listOf(result(isError = true)))
    assertEquals(AgentRunOutcome.ERRORED, errored.outcome)
    assertEquals(null, errored.cost)
  }

  @Test
  fun `an empty stream is an empty succeeded run`() {
    val mapped = AgentRunMapper.map(emptyList())
    assertEquals(emptyList<AgentStep>(), mapped.log.steps)
    assertEquals(AgentRunOutcome.SUCCEEDED, mapped.outcome)
  }

  private fun use(
      name: String,
      filePath: String? = null,
      command: String? = null,
      pattern: String? = null,
  ) = CldToolUse(name = name, filePath = filePath, command = command, pattern = pattern)

  private fun result(
      isError: Boolean,
      cost: Double? = null,
      turns: Int? = null,
      durationMs: Long? = null,
  ) =
      CldMessage.Result(
          isError = isError,
          subtype = null,
          totalCostUsd = cost,
          numTurns = turns,
          durationMs = durationMs,
      )
}
