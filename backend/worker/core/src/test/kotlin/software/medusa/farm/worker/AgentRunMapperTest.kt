package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import software.medusa.farm.claude.CldAssistantStep
import software.medusa.farm.claude.CldCost
import software.medusa.farm.claude.CldRunResult
import software.medusa.farm.claude.CldRunStatus
import software.medusa.farm.claude.CldSessionEvent
import software.medusa.farm.claude.CldToolUse
import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunEntry
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction
import software.medusa.farm.shared.AgentWarning

class AgentRunMapperTest {
  @Test
  fun `classifies each tool use into its semantic action`() {
    val step =
        CldAssistantStep(
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

    val mapped = AgentRunMapper.map(listOf(CldSessionEvent.Step(step)), okResult())
    val actions = mapped.log.entries.single().asStep().toolActions
    assertEquals("working", mapped.log.entries.single().asStep().text)
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
    val step = CldAssistantStep(text = "", toolUses = listOf(use("Edit")))
    assertEquals(
        listOf<AgentToolAction>(AgentToolAction.Other("Edit")),
        AgentRunMapper.map(listOf(CldSessionEvent.Step(step)), okResult())
            .log
            .entries
            .single()
            .asStep()
            .toolActions,
    )
  }

  @Test
  fun `maps the result to outcome and cost`() {
    val ok =
        AgentRunMapper.map(
            emptyList(),
            CldRunResult(CldRunStatus.Success, CldCost(0.42), 3, 1200.milliseconds),
        )
    assertEquals(AgentRunOutcome.SUCCEEDED, ok.outcome)
    assertEquals(AgentRunCost(usd = 0.42, turns = 3, durationMs = 1200), ok.cost)

    val errored =
        AgentRunMapper.map(
            emptyList(),
            CldRunResult(CldRunStatus.Error.parse("boom"), CldCost(0.05), 1, 9.milliseconds),
        )
    assertEquals(AgentRunOutcome.ERRORED, errored.outcome)
    // A run that ended badly still cost what it cost.
    assertEquals(AgentRunCost(usd = 0.05, turns = 1, durationMs = 9), errored.cost)
  }

  @Test
  fun `an empty run is an empty succeeded log`() {
    val mapped = AgentRunMapper.map(emptyList(), okResult())
    assertEquals(emptyList<AgentRunEntry>(), mapped.log.entries)
    assertEquals(AgentRunOutcome.SUCCEEDED, mapped.outcome)
  }

  private fun use(
      name: String,
      filePath: String? = null,
      command: String? = null,
      pattern: String? = null,
  ) = CldToolUse(name = name, filePath = filePath, command = command, pattern = pattern)

  private fun okResult() = CldRunResult(CldRunStatus.Success, CldCost(0.0), 0, 0.milliseconds)

  @Test
  fun `a warning is kept where it happened, among the steps`() {
    val mapped =
        AgentRunMapper.map(
            listOf(
                CldSessionEvent.Step(CldAssistantStep(text = "before", toolUses = emptyList())),
                CldSessionEvent.Warning("something is deprecated"),
                CldSessionEvent.Step(CldAssistantStep(text = "after", toolUses = emptyList())),
            ),
            okResult(),
        )

    assertEquals(
        listOf(
            AgentStep(text = "before", toolActions = emptyList()),
            AgentWarning(text = "something is deprecated"),
            AgentStep(text = "after", toolActions = emptyList()),
        ),
        mapped.log.entries,
    )
  }

  private fun AgentRunEntry.asStep(): AgentStep = assertIs<AgentStep>(this)
}
