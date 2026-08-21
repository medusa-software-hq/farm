package software.medusa.farm.shared

/**
 * Where a processing session is in its lifecycle. Terminal states are COMPLETED and FAILED.
 *
 * RUNNING and AWAITING_REVIEW are both live, and what separates them is who is being waited on:
 * Farm in the one, a person with a pull request in front of them in the other.
 */
enum class SessionState {
  RUNNING,
  AWAITING_REVIEW,
  COMPLETED,
  FAILED;

  /** Whether the session is over: nothing further will happen to it. */
  val finished: Boolean
    get() = this == COMPLETED || this == FAILED
}
