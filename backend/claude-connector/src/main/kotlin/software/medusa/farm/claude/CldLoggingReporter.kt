package software.medusa.farm.claude

import java.util.logging.Logger

/** Logs each run-contract anomaly via [java.util.logging] so a degraded run isn't swallowed. */
class CldLoggingReporter : CldReporter {
  private val logger: Logger = Logger.getLogger("software.medusa.farm.claude")

  override fun missingInit() {
    logger.warning("claude stream did not open with an init message")
  }

  override fun messageAfterResult(line: String) {
    logger.warning("claude emitted a message after the terminal result: ${line.take(LINE_TAIL)}")
  }

  override fun exitWithoutResult(exitCode: Int, standardError: String) {
    logger.warning(
        "claude exited $exitCode without a result; stderr: ${standardError.takeLast(STDERR_TAIL)}"
    )
  }

  private companion object {
    const val LINE_TAIL = 500
    const val STDERR_TAIL = 2_000
  }
}
