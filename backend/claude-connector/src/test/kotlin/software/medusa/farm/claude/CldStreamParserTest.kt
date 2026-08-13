package software.medusa.farm.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CldStreamParserTest {
  @Test
  fun `blank and non-object lines are skipped`() {
    assertNull(CldStreamParser.parseLine(""))
    assertNull(CldStreamParser.parseLine("   "))
    assertNull(CldStreamParser.parseLine("not json"))
    assertNull(CldStreamParser.parseLine("[1, 2, 3]"))
  }

  @Test
  fun `system init carries session id, model, and tools`() {
    val message =
        CldStreamParser.parseLine(
            """{"type":"system","subtype":"init","session_id":"abc","model":"opus",""" +
                """"tools":["Read","Bash"]}"""
        )
    val init = assertIs<CldMessage.SystemInit>(message)
    assertEquals("abc", init.sessionId)
    assertEquals("opus", init.model)
    assertEquals(listOf("Read", "Bash"), init.tools)
  }

  @Test
  fun `a non-init system message is unknown, not a crash`() {
    val message = CldStreamParser.parseLine("""{"type":"system","subtype":"compact"}""")
    assertEquals(CldMessage.Unknown(type = "system"), message)
  }

  @Test
  fun `assistant text blocks are concatenated and tool uses summarized`() {
    val message =
        CldStreamParser.parseLine(
            """{"type":"assistant","message":{"content":[""" +
                """{"type":"text","text":"Looking into it"},""" +
                """{"type":"tool_use","name":"Edit","input":{"file_path":"src/A.kt"}},""" +
                """{"type":"tool_use","name":"Bash","input":{"command":"gradle test\nmore"}}""" +
                """]}}"""
        )
    val assistant = assertIs<CldMessage.Assistant>(message)
    assertEquals("Looking into it", assistant.text)
    assertEquals(listOf("edited `src/A.kt`", "ran `gradle test`"), assistant.toolActions)
  }

  @Test
  fun `an unrecognized tool degrades to a generic summary`() {
    val message =
        CldStreamParser.parseLine(
            """{"type":"assistant","message":{"content":[""" +
                """{"type":"tool_use","name":"Sorcery","input":{}}]}}"""
        )
    val assistant = assertIs<CldMessage.Assistant>(message)
    assertEquals(listOf("used Sorcery"), assistant.toolActions)
  }

  @Test
  fun `result carries the verdict and accounting`() {
    val message =
        CldStreamParser.parseLine(
            """{"type":"result","subtype":"error_max_budget_usd","is_error":true,""" +
                """"total_cost_usd":1.5,"num_turns":7,"duration_ms":1234}"""
        )
    val result = assertIs<CldMessage.Result>(message)
    assertTrue(result.isError)
    assertEquals("error_max_budget_usd", result.subtype)
    assertEquals(1.5, result.totalCostUsd)
    assertEquals(7, result.numTurns)
    assertEquals(1234L, result.durationMs)
  }

  @Test
  fun `unknown fields and message types degrade instead of throwing`() {
    assertEquals(
        CldMessage.Unknown(type = "future"),
        CldStreamParser.parseLine("""{"type":"future"}"""),
    )
    // An unexpected extra field on a known type is ignored, not fatal.
    val message = CldStreamParser.parseLine("""{"type":"result","is_error":false,"surprise":42}""")
    val result = assertIs<CldMessage.Result>(message)
    assertEquals(false, result.isError)
    assertNull(result.totalCostUsd)
  }
}
