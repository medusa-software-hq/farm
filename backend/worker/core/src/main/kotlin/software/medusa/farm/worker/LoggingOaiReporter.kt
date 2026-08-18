package software.medusa.farm.worker

import java.util.logging.Level
import java.util.logging.Logger
import software.medusa.commons.openai_client.OaiReporter

/**
 * Logs each anomaly the openai-client folds into its coarse result tree instead of raising, so a
 * degraded summary run isn't silently swallowed. Uses [java.util.logging] to avoid a logging dep.
 */
class LoggingOaiReporter : OaiReporter {
  private val logger: Logger = Logger.getLogger("software.medusa.farm.worker.summary")

  override fun reportNoChoices() {
    logger.warning("OpenAI response contained no choices")
  }

  override fun reportMultipleChoices(choiceCount: Int) {
    logger.warning("OpenAI response contained multiple choices ($choiceCount); using the first")
  }

  override fun reportMissingTokenUsage() {
    logger.warning("OpenAI response did not include token usage")
  }

  override fun reportEmptyResponse() {
    logger.warning("OpenAI response choice contained neither content nor tool calls")
  }

  override fun reportUnknownFinishReason(finishReason: String) {
    logger.warning("OpenAI reported an unrecognized finish reason: $finishReason")
  }

  override fun reportInvalidToolName(rawToolName: String, cause: Throwable) {
    logger.log(Level.WARNING, "OpenAI returned an invalid tool name: $rawToolName", cause)
  }

  override fun reportMalformedToolCallArguments(rawArguments: String, cause: Throwable) {
    logger.log(Level.WARNING, "OpenAI returned malformed tool-call arguments: $rawArguments", cause)
  }

  override fun reportIoError(cause: Throwable) {
    logger.log(Level.WARNING, "Network I/O error during chat completion", cause)
  }

  override fun reportClientError(cause: Throwable) {
    logger.log(Level.WARNING, "OpenAI client error during chat completion", cause)
  }
}
