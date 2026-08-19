package software.medusa.farm.claude

import kotlin.time.Duration

/** What a finished Claude session amounted to. */
data class CldRunResult(
    /** How the session ended. */
    val status: CldRunStatus,

    /** Money spent over the whole session. */
    val totalCost: CldCost,

    /** Number of turns the assistant took. */
    val turnCount: Int,

    /** How long the session lasted. */
    val sessionDuration: Duration,
)
