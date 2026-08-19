package software.medusa.farm.claude

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The engine's opening greeting, describing the session it has just started. */
internal data class CldSystemInitMessage(
    val sessionInfo: CldSessionInfo,
) : CldMessage {
  companion object {
    private const val messageType: String = "system"

    private const val messageSubtype: String = "init"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the greeting out of [jsonString].
     *
     * @return The greeting, or null if [jsonString] is not one.
     */
    fun parse(
        jsonString: String,
    ): CldSystemInitMessage? {
      val raw =
          try {
            json.decodeFromString<RawSystemInitMessage>(jsonString)
          } catch (_: SerializationException) {
            return null
          } catch (_: IllegalArgumentException) {
            return null
          }

      if (raw.type != messageType || raw.subtype != messageSubtype) return null

      return CldSystemInitMessage(
          sessionInfo =
              CldSessionInfo(
                  sessionId = CldSessionId(id = raw.sessionId),
                  modelId = CldModelId(id = raw.model),
                  availableToolSpecifiers = raw.tools.map { CldToolRule.parse(it) }.toSet(),
              )
      )
    }
  }
}

@Serializable
private data class RawSystemInitMessage(
    val type: String,
    val subtype: String? = null,
    @SerialName("session_id") val sessionId: String,
    val model: String,
    val tools: List<String> = emptyList(),
)
