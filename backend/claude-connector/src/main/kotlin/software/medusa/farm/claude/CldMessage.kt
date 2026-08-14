package software.medusa.farm.claude

/**
 * The subset of the `claude` CLI's `stream-json` (NDJSON) protocol the connector acts on.
 *
 * The model is intentionally partial and lenient: [CldStreamParser] tolerates unknown message
 * `type`s and unknown fields, and every wire field the connector reads is confined to that one
 * parser. Protocol drift is therefore a localized fix, not something scattered across callers.
 */
sealed interface CldMessage {
  /**
   * The opening `system`/`init` banner. Carries the run's identity: its [sessionId] (the resume
   * anchor), the resolved [model], and the [tools] the CLI enabled.
   */
  data class SystemInit(
      val sessionId: String?,
      val model: String?,
      val tools: List<String>,
  ) : CldMessage

  /**
   * An `assistant` turn: its concatenated [text] blocks plus the structured [toolUses] for any
   * `tool_use` blocks. Either may be empty. The consumer classifies the tool uses into semantic
   * actions.
   */
  data class Assistant(
      val text: String,
      val toolUses: List<CldToolUse>,
  ) : CldMessage

  /**
   * The terminal `result` message. [isError] plus [subtype] carry the outcome — including cap trips
   * such as `error_max_budget_usd` — and the accounting fields feed the run's cost.
   */
  data class Result(
      val isError: Boolean,
      val subtype: String?,
      val totalCostUsd: Double?,
      val numTurns: Int?,
      val durationMs: Long?,
  ) : CldMessage

  /**
   * Any message whose `type` the connector does not model — captured so nothing throws on drift.
   */
  data class Unknown(
      val type: String?,
  ) : CldMessage
}
