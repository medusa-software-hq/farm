package software.medusa.farm.github

/** How far along a check run is. */
enum class GhCheckRunStatus {
  QUEUED,
  IN_PROGRESS,

  /** Over, so its conclusion is the check's verdict rather than a guess at where it is heading. */
  COMPLETED,

  /** A status this library does not know by name; carried rather than guessed at. */
  UNRECOGNIZED,
}
