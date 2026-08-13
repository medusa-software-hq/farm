package software.medusa.farm.claude

/** The accounting the CLI reports in its terminal `result` message. Any field may be absent. */
data class CldRunCost(
    val totalCostUsd: Double?,
    val numTurns: Int?,
    val durationMs: Long?,
)
