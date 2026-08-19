package software.medusa.farm.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class AgentRunLogSerializationTest {
  @Test
  fun `round-trips through json including every sealed tool action`() {
    val log =
        AgentRunLog(
            entries =
                listOf(
                    AgentStep(
                        text = "did the thing",
                        toolActions =
                            listOf(
                                AgentToolAction.EditFile("src/A.kt"),
                                AgentToolAction.ReadFile("src/B.kt"),
                                AgentToolAction.RunCommand("gradle test"),
                                AgentToolAction.Search("TODO"),
                                AgentToolAction.Other("mcp__server__tool"),
                            ),
                    ),
                    AgentWarning(text = "something is deprecated"),
                    AgentStep(text = "", toolActions = emptyList()),
                )
        )

    val encoded = Json.encodeToString(log)
    assertEquals(log, Json.decodeFromString<AgentRunLog>(encoded))
  }

  @Test
  fun `an entry is written under the name stored rows are migrated to`() {
    // The log is stored as JSON, so what a run is written as outlives the run. A rename here is a
    // rename of the stored format, and needs a migration to go with it.
    val encoded = Json.encodeToString(AgentRunLog(entries = listOf(AgentWarning(text = "careful"))))

    assertEquals(
        """{"entries":[{"type":"software.medusa.farm.shared.AgentWarning","text":"careful"}]}""",
        encoded,
    )
  }

  @Test
  fun `a row the migration rewrote decodes as the entries it was rewritten into`() {
    // Verbatim output of V13 run against a row written in the old shape, so the migration is held
    // to what actually has to read it rather than to a hand-written idea of its result.
    val migrated =
        """{"entries": [{"text": "first", "type": "software.medusa.farm.shared.AgentStep", "toolActions": [{"path": "src/A.kt", "type": "software.medusa.farm.shared.AgentToolAction.EditFile"}]}, {"text": "second", "type": "software.medusa.farm.shared.AgentStep", "toolActions": []}]}"""

    assertEquals(
        AgentRunLog(
            entries =
                listOf(
                    AgentStep(
                        text = "first",
                        toolActions = listOf(AgentToolAction.EditFile("src/A.kt")),
                    ),
                    AgentStep(text = "second", toolActions = emptyList()),
                )
        ),
        Json.decodeFromString<AgentRunLog>(migrated),
    )
  }

  @Test
  fun `an entry row the migration wrote decodes on its own`() {
    // Entries are stored one row each now, so a row has to decode by itself rather than as part of
    // a log. Verbatim output of V14 run against a run recorded in the old blob shape.
    val migrated = """{"text": "careful", "type": "software.medusa.farm.shared.AgentWarning"}"""

    assertEquals(AgentWarning(text = "careful"), Json.decodeFromString<AgentRunEntry>(migrated))
  }
}
