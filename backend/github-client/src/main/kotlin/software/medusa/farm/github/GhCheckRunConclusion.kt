package software.medusa.farm.github

/** How a completed check run came out. */
enum class GhCheckRunConclusion {
  SUCCESS,
  FAILURE,
  NEUTRAL,
  CANCELLED,
  TIMED_OUT,
  ACTION_REQUIRED,
  SKIPPED,
  STALE,
  STARTUP_FAILURE,

  /** A conclusion this library does not know by name; carried rather than guessed at. */
  UNRECOGNIZED,
}
