package software.medusa.farm.claude

import java.util.logging.Level
import java.util.logging.Logger

/** Logs each run-contract anomaly via [java.util.logging] so a degraded run isn't swallowed. */
class CldLoggingReporter : CldReporter {
  private val logger: Logger = Logger.getLogger("software.medusa.farm.claude")

  override fun spawnFailed(cause: Throwable) {
    logger.log(Level.WARNING, "claude could not be started", cause)
  }

  override fun missingInit(opening: String?) {
    logger.warning(
        "claude stream did not open with an init message; first line: " +
            (opening?.take(LINE_TAIL) ?: "<none>")
    )
  }

  override fun streamFailed(cause: Throwable) {
    logger.log(Level.WARNING, "claude stream could not be read through to a result", cause)
  }

  override fun messageAfterResult(line: String) {
    logger.warning("claude emitted a message after the terminal result: ${line.take(LINE_TAIL)}")
  }

  override fun exitWithoutResult(exitCode: Int, standardError: String) {
    logger.warning(
        "claude exited $exitCode without a result; stderr: ${standardError.takeLast(STDERR_TAIL)}"
    )
  }

  override fun lingeredAfterResult() {
    logger.warning("claude emitted its result but did not exit promptly")
  }

  override fun exitDisagreedWithResult(exitCode: Int) {
    logger.warning("claude exited $exitCode despite a successful result")
  }

  private companion object {
    const val LINE_TAIL = 500
    const val STDERR_TAIL = 2_000
  }
}
