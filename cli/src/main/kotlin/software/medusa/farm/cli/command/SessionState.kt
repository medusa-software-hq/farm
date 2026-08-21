package software.medusa.farm.cli.command

/**
 * What a session's state means to somebody reading it, rather than what it is called.
 *
 * The service sends a string and may send one this does not know, so an unknown state is shown
 * rather than mistaken for a known one.
 */
internal object SessionState {
  private const val RUNNING = "RUNNING"
  private const val AWAITING_REVIEW = "AWAITING_REVIEW"
  private const val COMPLETED = "COMPLETED"
  private const val FAILED = "FAILED"

  /** Whether Farm is still on this session, so following it has an end to wait for. */
  fun isLive(state: String): Boolean = state == RUNNING || state == AWAITING_REVIEW

  fun describe(state: String): String =
      when (state) {
        RUNNING -> "working"
        AWAITING_REVIEW -> "awaiting review — the next move is yours"
        COMPLETED -> "completed"
        FAILED -> "failed"
        else -> state.lowercase()
      }
}
