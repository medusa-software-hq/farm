package software.medusa.farm.claude

/**
 * Where the detail behind a [CldError] goes.
 *
 * Every anomaly reported here ends the session it belongs to; none of them is tolerated. Keeping
 * the detail off the error itself is what lets a caller stay incurious about the shape of a broken
 * engine while still leaving a trail for whoever has to work out why it broke.
 */
interface CldAnomalyReporter {
  /** The engine could not be started; [cause] says why. */
  fun reportSpawnFailed(cause: Throwable)

  /**
   * The engine's first word was [firstLine] instead of the expected greeting, or nothing at all.
   */
  fun reportMissingInitMessage(firstLine: String?)

  /** The engine started on [reported] instead of the [requested] model. */
  fun reportModelMismatch(requested: CldModelId, reported: CldModelId)

  /** The engine said something unintelligible mid-session. */
  fun reportUnexpectedProgressLine(progressLine: String)

  /** The engine kept talking after reporting its result. */
  fun reportOutputAfterResult(outputLine: String)

  /** The engine ended without ever reporting a result. */
  fun reportExitWithoutResult(exitCode: Int)

  /** The engine reported its result and then neither said more nor fell silent. */
  fun reportHangOutput()

  /** The engine reported its result and then would not end. */
  fun reportLingeredAfterResult()

  /** The engine claimed success and then ended as though it had failed. */
  fun reportUnexpectedNonZeroExitCode(exitCode: Int, runResult: CldRunResult)

  /** The engine claimed failure and then ended as though it had succeeded. */
  fun reportUnexpectedZeroExitCode(runResult: CldRunResult)
}
