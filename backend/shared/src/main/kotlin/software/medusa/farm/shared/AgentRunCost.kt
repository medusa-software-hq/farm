package software.medusa.farm.shared

import kotlinx.serialization.Serializable

/**
 * What a run cost, when the backend reports it. Held whole ([SessionRun.cost] is null when the
 * backend reported nothing) so there is one absence to reason about, not three.
 */
@Serializable
data class AgentRunCost(
    val usd: Double,
    val turns: Int,
    val durationMs: Long,
)
