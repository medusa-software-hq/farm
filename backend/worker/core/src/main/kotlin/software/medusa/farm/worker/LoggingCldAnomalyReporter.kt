package software.medusa.farm.worker

import org.slf4j.LoggerFactory
import software.medusa.farm.claude.CldAnomalyReporter
import software.medusa.farm.claude.CldRunResult

/**
 * Logs each way the engine misbehaved, so a failed session leaves a trail behind its opaque error.
 */
class LoggingCldAnomalyReporter : CldAnomalyReporter {
  private val logger = LoggerFactory.getLogger(LoggingCldAnomalyReporter::class.java)

  override fun reportSpawnFailed(cause: Throwable) {
    logger.warn("claude could not be started", cause)
  }

  override fun reportMissingInitMessage(firstLine: String?) {
    logger.warn("claude did not greet us; it opened with: ${firstLine ?: "<nothing>"}")
  }

  override fun reportUnexpectedProgressLine(progressLine: String) {
    logger.warn("claude said something unintelligible mid-session: $progressLine")
  }

  override fun reportOutputAfterResult(outputLine: String) {
    logger.warn("claude kept talking after its result: $outputLine")
  }

  override fun reportExitWithoutResult(exitCode: Int) {
    logger.warn("claude ended with $exitCode without ever reporting a result")
  }

  override fun reportHangOutput() {
    logger.warn("claude reported its result and then neither said more nor fell silent")
  }

  override fun reportLingeredAfterResult() {
    logger.warn("claude reported its result and then would not end")
  }

  override fun reportUnexpectedNonZeroExitCode(exitCode: Int, runResult: CldRunResult) {
    logger.warn("claude claimed ${runResult.status} and then ended with $exitCode")
  }

  override fun reportUnexpectedZeroExitCode(runResult: CldRunResult) {
    logger.warn("claude claimed ${runResult.status} and then ended cleanly")
  }
}
