package software.medusa.farm.shared

/**
 * One agent run within a session: [ordinal] 0 is the initial attempt, 1+ are fixup runs. A run is
 * what was asked for; an entry in [attempts] is a try at it, in the order they were made. There is
 * more than one only where a try was retried.
 */
data class SessionRun(
    val ordinal: Int,
    val attempts: List<SessionRunAttempt>,
)
