package software.medusa.farm.claude

/**
 * Sink for run-contract anomalies the driver would otherwise have to bake into return values or
 * swallow. claude's stream-json is expected to open with an `init` message and end with exactly one
 * terminal `result`; deviations are reported here — and logged, see [CldLoggingReporter] — rather
 * than thrown, except the one that also fails the run ([exitWithoutResult]).
 */
interface CldReporter {
  /** The stream did not open with an `init` message. */
  fun missingInit()

  /** A message arrived after the terminal `result` — nothing should follow it. */
  fun messageAfterResult(line: String)

  /** The process exited without ever emitting a `result`; the run also fails. */
  fun exitWithoutResult(exitCode: Int, standardError: String)

  /** The process emitted its `result` but did not exit promptly afterwards. */
  fun lingeredAfterResult()

  /** The process exited non-zero despite reporting a successful `result`. */
  fun exitDisagreedWithResult(exitCode: Int)
}
