package software.medusa.farm.shared

import java.time.Instant

/**
 * One agent run within a session: [ordinal] 0 is the initial attempt, 1+ are fixup runs. Carries
 * the backend-neutral action [log] — which grows while the run is going — and, once there is
 * anything to say about it, how it went.
 */
sealed interface SessionRun {
  val ordinal: Int

  val log: AgentRunLog

  val createdAt: Instant

  /** A run still under way. Its log is what it has done so far, not what it will have done. */
  data class Running(
      override val ordinal: Int,
      override val log: AgentRunLog,
      override val createdAt: Instant,
  ) : SessionRun

  /** A run that is over, with its [outcome], its [summary], and — when reported — its [cost]. */
  data class Finished(
      override val ordinal: Int,
      override val log: AgentRunLog,
      val outcome: AgentRunOutcome,
      val cost: AgentRunCost?,
      val summary: String,
      override val createdAt: Instant,
  ) : SessionRun
}
