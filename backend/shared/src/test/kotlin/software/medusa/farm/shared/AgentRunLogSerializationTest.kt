package software.medusa.farm.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class AgentRunLogSerializationTest {
  @Test
  fun `round-trips through json including every sealed tool action`() {
    val log =
        AgentRunLog(
            steps =
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
                    AgentStep(text = "", toolActions = emptyList()),
                )
        )

    val encoded = Json.encodeToString(log)
    assertEquals(log, Json.decodeFromString<AgentRunLog>(encoded))
  }
}
