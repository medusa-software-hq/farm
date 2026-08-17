package software.medusa.farm.claude

/**
 * How the agent's own run ended, as the CLI reported it — *not* the downstream domain outcome (that
 * is assembled by the caller from this plus the git diff and any abort). An operational failure
 * never reaches here; it is thrown as [CldLaunchException] or [CldRunException].
 */
sealed interface CldCompletion {
  /** The `result` message reported success. */
  data object Ok : CldCompletion

  /** The `result` message reported an error; [subtype] carries the reason (e.g. a budget trip). */
  data class Errored(val subtype: String?) : CldCompletion
}
