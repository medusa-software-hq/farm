package software.medusa.farm.claude

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses one line of the `claude` CLI's `stream-json` output into a [CldMessage].
 *
 * All wire-field access lives here on purpose (see [CldMessage]): keeping every key lookup in this
 * one object makes protocol drift a single-file fix. Navigation is done over a raw [JsonObject]
 * rather than `@Serializable` classes so an unexpected or renamed field degrades to `null` instead
 * of throwing.
 */
object CldStreamParser {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Parses [line] into a message, or returns `null` for a blank line or one that is not a JSON
   * object — both are skipped by the driver rather than treated as failures.
   */
  fun parseLine(line: String): CldMessage? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    val root =
        try {
          json.parseToJsonElement(trimmed).jsonObject
        } catch (_: SerializationException) {
          return null
        } catch (_: IllegalArgumentException) {
          return null
        }

    return when (val type = root.stringField("type")) {
      "system" -> parseSystem(root)
      "assistant" -> parseAssistant(root)
      "result" -> parseResult(root)
      else -> CldMessage.Unknown(type = type)
    }
  }

  private fun parseSystem(root: JsonObject): CldMessage =
      when (root.stringField("subtype")) {
        "init" ->
            CldMessage.SystemInit(
                sessionId = root.stringField("session_id"),
                model = root.stringField("model"),
                tools =
                    root["tools"]?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList(),
            )
        else -> CldMessage.Unknown(type = "system")
      }

  private fun parseAssistant(root: JsonObject): CldMessage {
    // Shape: {"type":"assistant","message":{"content":[
    //   {"type":"text","text":"…"},
    //   {"type":"tool_use","name":"Edit","input":{"file_path":"x/y.kt", …}}, … ]}}
    val content = root["message"]?.jsonObjectOrNull()?.get("content")?.jsonArrayOrNull()
    val blocks = content?.mapNotNull { it.jsonObjectOrNull() }.orEmpty()

    val text =
        blocks
            .filter { it.stringField("type") == "text" }
            .mapNotNull { it.stringField("text") }
            .joinToString(separator = "\n")

    val toolUses = blocks.filter { it.stringField("type") == "tool_use" }.mapNotNull { toolUse(it) }

    return CldMessage.Assistant(text = text, toolUses = toolUses)
  }

  /**
   * Reads a `tool_use` block into a [CldToolUse]: the tool name plus the input fields the connector
   * exposes. All tool-use wire access lives here so callers stay protocol-agnostic.
   */
  private fun toolUse(block: JsonObject): CldToolUse? {
    val name = block.stringField("name") ?: return null
    val input = block["input"]?.jsonObjectOrNull()
    return CldToolUse(
        name = name,
        filePath = input?.stringField("file_path"),
        command = input?.stringField("command"),
        pattern = input?.stringField("pattern"),
    )
  }

  private fun parseResult(root: JsonObject): CldMessage =
      CldMessage.Result(
          isError =
              root["is_error"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
          subtype = root.stringField("subtype"),
          totalCostUsd = root["total_cost_usd"]?.jsonPrimitive?.doubleOrNull,
          numTurns = root["num_turns"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
          durationMs = root["duration_ms"]?.jsonPrimitive?.longOrNull,
      )

  private fun JsonObject.stringField(name: String): String? =
      this[name]?.jsonPrimitive?.contentOrNull

  private fun JsonElement.jsonObjectOrNull(): JsonObject? =
      try {
        jsonObject
      } catch (_: IllegalArgumentException) {
        null
      }

  private fun JsonElement.jsonArrayOrNull(): JsonArray? =
      try {
        jsonArray
      } catch (_: IllegalArgumentException) {
        null
      }
}
