package software.medusa.farm.claude

/**
 * The library's observability seam for run-contract anomalies — the moments claude behaves
 * differently than the connector statically assumes (no `init`, a message after the terminal
 * `result`, an exit that disagrees with the verdict). It keeps the public API abstraction-clean:
 * exit codes, stderr, and causes never appear on results or on [CldLaunchException] /
 * [CldRunException], which the caller can't act on anyway. They land here instead, so a degraded
 * run is never silently swallowed and the detail is there to correct the connector's model of
 * claude in a later version. Some anomalies are reported and tolerated (the run still completes);
 * others are reported and then fail the run — the reporting call is the same either way.
 */
interface CldReporter {
  /** The binary could not be started; [cause] is the spawn failure. The launch fails. */
  fun spawnFailed(cause: Throwable)

  /**
   * The stream did not open with an `init`; [opening] is the offending first line, or null if none
   * arrived in time. The launch fails.
   */
  fun missingInit(opening: String?)

  /** Reading or parsing stdout failed mid-run; [cause] is the failure. The run fails. */
  fun streamFailed(cause: Throwable)

  /** A message arrived after the terminal `result` — nothing should follow it. Tolerated. */
  fun messageAfterResult(line: String)

  /** The process exited without ever emitting a `result`. The run fails. */
  fun exitWithoutResult(exitCode: Int, standardError: String)

  /** The process emitted its `result` but did not exit promptly afterwards. Tolerated. */
  fun lingeredAfterResult()

  /** The process exited non-zero despite reporting a successful `result`. Tolerated. */
  fun exitDisagreedWithResult(exitCode: Int)
}
