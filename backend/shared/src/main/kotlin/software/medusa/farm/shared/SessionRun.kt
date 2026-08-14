package software.medusa.farm.shared

import java.time.Instant

/**
 * One agent run within a session: [ordinal] 0 is the initial attempt, 1+ are fixup runs. Carries
 * the backend-neutral action [log] plus the run's metadata (its [outcome] and, when reported, its
 * [cost]).
 */
data class SessionRun(
    val ordinal: Int,
    val log: AgentRunLog,
    val outcome: AgentRunOutcome,
    val cost: AgentRunCost?,
    val summary: String?,
    val createdAt: Instant,
)
