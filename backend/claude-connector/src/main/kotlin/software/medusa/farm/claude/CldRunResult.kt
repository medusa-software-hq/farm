package software.medusa.farm.claude

/**
 * The terminal outcome of a run. The run's identity ([CldRunInfo.sessionId]) is on the [CldRun]
 * from the start; this is only what the *end* of the run reports.
 *
 * @property completion how the CLI said the run ended.
 * @property cost the reported accounting, if the run produced a `result` message.
 */
data class CldRunResult(
    val completion: CldCompletion,
    val cost: CldRunCost?,
)
