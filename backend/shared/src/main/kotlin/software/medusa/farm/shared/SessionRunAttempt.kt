package software.medusa.farm.shared

import java.time.Instant

/**
 * One try at an agent run, numbered as the activity attempt it ran under so that a try recorded
 * here and a try in the workflow's history are the same thing.
 *
 * A try that is not [Finished] left no account of why. What separates the two unfinished states is
 * only whether anything came after: a try nothing succeeded may still be going, and a try something
 * succeeded is over however it ended.
 */
sealed interface SessionRunAttempt {
  val number: Int

  val log: AgentRunLog

  val startedAt: Instant

  /** The latest try, still under way. Its log is what it has done so far. */
  data class Running(
      override val number: Int,
      override val log: AgentRunLog,
      override val startedAt: Instant,
  ) : SessionRunAttempt

  /** A try that stopped without closing and was retried. Kept for reading back what it got to. */
  data class Abandoned(
      override val number: Int,
      override val log: AgentRunLog,
      override val startedAt: Instant,
  ) : SessionRunAttempt

  /**
   * A try that ran to an end, with its [outcome], its [summary] and — when reported — its [cost].
   */
  data class Finished(
      override val number: Int,
      override val log: AgentRunLog,
      val outcome: AgentRunOutcome,
      val cost: AgentRunCost?,
      val summary: String,
      override val startedAt: Instant,
  ) : SessionRunAttempt
}
