package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.farm.claude.CldCompletion
import software.medusa.farm.claude.CldRunCost
import software.medusa.farm.claude.CldRunResult
import software.medusa.farm.claude.CldStep
import software.medusa.farm.claude.CldToolUse
import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

class AgentRunMapperTest {
  @Test
  fun `classifies each tool use into its semantic action`() {
    val step =
        CldStep(
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

    val mapped = AgentRunMapper.map(listOf(step), okResult())
    val actions = mapped.log.steps.single().toolActions
    assertEquals("working", mapped.log.steps.single().text)
    assertEquals(
        listOf(
            AgentToolAction.EditFile("src/A.kt"),
            AgentToolAction.EditFile("src/B.kt"),
            AgentToolAction.ReadFile("src/C.kt"),
            AgentToolAction.RunCommand("gradle test"),
            AgentToolAction.Search("TODO"),
            AgentToolAction.Other("Sorcery"),
        ),
        actions,
    )
  }

  @Test
  fun `a tool use missing its wire field degrades to Other`() {
    val step = CldStep(text = "", toolUses = listOf(use("Edit")))
    assertEquals(
        listOf<AgentToolAction>(AgentToolAction.Other("Edit")),
        AgentRunMapper.map(listOf(step), okResult()).log.steps.single().toolActions,
    )
  }

  @Test
  fun `maps the result to outcome and cost`() {
    val ok =
        AgentRunMapper.map(
            emptyList(),
            CldRunResult(CldCompletion.Ok, CldRunCost(0.42, 3, 1200)),
        )
    assertEquals(AgentRunOutcome.SUCCEEDED, ok.outcome)
    assertEquals(AgentRunCost(usd = 0.42, turns = 3, durationMs = 1200), ok.cost)

    val errored = AgentRunMapper.map(emptyList(), CldRunResult(CldCompletion.Errored("boom"), null))
    assertEquals(AgentRunOutcome.ERRORED, errored.outcome)
    assertEquals(null, errored.cost)
  }

  @Test
  fun `an incomplete cost is dropped whole`() {
    val mapped =
        AgentRunMapper.map(
            emptyList(),
            CldRunResult(CldCompletion.Ok, CldRunCost(0.1, null, 5)),
        )
    assertEquals(null, mapped.cost)
  }

  @Test
  fun `an empty run is an empty succeeded log`() {
    val mapped = AgentRunMapper.map(emptyList(), okResult())
    assertEquals(emptyList<AgentStep>(), mapped.log.steps)
    assertEquals(AgentRunOutcome.SUCCEEDED, mapped.outcome)
  }

  private fun use(
      name: String,
      filePath: String? = null,
      command: String? = null,
      pattern: String? = null,
  ) = CldToolUse(name = name, filePath = filePath, command = command, pattern = pattern)

  private fun okResult() = CldRunResult(CldCompletion.Ok, null)
}
