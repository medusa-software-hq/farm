package software.medusa.farm.claude

/**
 * The outcome of a run, as far as the connector can see it.
 *
 * @property sessionId the session the run executed under — the anchor to snapshot and later resume.
 * @property completion how the CLI said the run ended.
 * @property cost the reported accounting, if the run produced a `result` message.
 */
data class CldRunResult(
    val sessionId: String,
    val completion: CldCompletion,
    val cost: CldRunCost?,
)
