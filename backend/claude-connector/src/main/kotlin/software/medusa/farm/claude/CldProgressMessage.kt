package software.medusa.farm.claude

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Something the engine said while a session was under way. */
internal sealed interface CldProgressMessage : CldMessage {
  companion object {
    private const val assistantType: String = "assistant"

    private const val resultType: String = "result"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads out of [jsonString] whatever the engine said.
     *
     * @return What was said, or null if [jsonString] is not something the engine could have said at
     *   this point at all.
     */
    fun parse(
        jsonString: String,
    ): CldProgressMessage? =
        try {
          when (val type = json.decodeFromString<RawProgressMessage>(jsonString).type) {
            assistantType -> parseAssistantStep(jsonString = jsonString)
            resultType -> parseResult(jsonString = jsonString)
            else -> Other(type = type)
          }
        } catch (_: SerializationException) {
          null
        } catch (_: IllegalArgumentException) {
          null
        }

    private fun parseAssistantStep(
        jsonString: String,
    ): AssistantStep {
      val blocks = json.decodeFromString<RawAssistantMessage>(jsonString).message.content

      return AssistantStep(
          assistantStep =
              CldAssistantStep(
                  text =
                      blocks
                          .filter { it.type == textBlockType }
                          .mapNotNull { it.text }
                          .joinToString(separator = "\n"),
                  toolUses =
                      blocks
                          .filter { it.type == toolUseBlockType }
                          .mapNotNull { block ->
                            block.name?.let { name ->
                              CldToolUse(
                                  name = name,
                                  filePath = block.input?.filePath,
                                  command = block.input?.command,
                                  pattern = block.input?.pattern,
                              )
                            }
                          },
              )
      )
    }

    private fun parseResult(
        jsonString: String,
    ): Result {
      val raw = json.decodeFromString<RawResultMessage>(jsonString)

      return Result(
          runResult =
              CldRunResult(
                  status =
                      when {
                        raw.isError -> CldRunStatus.Error.parse(type = raw.subtype)
                        else -> CldRunStatus.Success
                      },
                  totalCost = CldCost(usdAmount = raw.totalCostUsd),
                  turnCount = raw.numTurns,
                  sessionDuration = raw.durationMs.milliseconds,
              )
      )
    }

    private const val textBlockType: String = "text"

    private const val toolUseBlockType: String = "tool_use"
  }

  /** A step the assistant took. */
  data class AssistantStep(
      val assistantStep: CldAssistantStep,
  ) : CldProgressMessage

  /** The session's closing word on how it went. */
  data class Result(
      val runResult: CldRunResult,
  ) : CldProgressMessage

  /**
   * Something of a kind this library does not act on. Passing these over rather than balking at
   * them is what lets an engine that grows new things to say stay usable.
   */
  data class Other(
      val type: String,
  ) : CldProgressMessage
}

@Serializable private data class RawProgressMessage(val type: String)

@Serializable private data class RawAssistantMessage(val message: RawAssistantBody)

@Serializable
private data class RawAssistantBody(
    val content: List<RawContentBlock> = emptyList(),
)

@Serializable
private data class RawContentBlock(
    val type: String,
    val text: String? = null,
    val name: String? = null,
    val input: RawToolInput? = null,
)

@Serializable
private data class RawToolInput(
    @SerialName("file_path") val filePath: String? = null,
    val command: String? = null,
    val pattern: String? = null,
)

@Serializable
private data class RawResultMessage(
    val subtype: String,
    @SerialName("is_error") val isError: Boolean = false,
    @SerialName("total_cost_usd") val totalCostUsd: Double = 0.0,
    @SerialName("num_turns") val numTurns: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)
