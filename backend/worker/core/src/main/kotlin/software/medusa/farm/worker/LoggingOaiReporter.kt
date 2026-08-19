package software.medusa.farm.worker

import org.slf4j.LoggerFactory
import software.medusa.commons.openai_client.OaiReporter

/**
 * Logs each anomaly the openai-client folds into its coarse result tree instead of raising, so a
 * degraded summary run isn't silently swallowed.
 */
class LoggingOaiReporter : OaiReporter {
  private val logger = LoggerFactory.getLogger(LoggingOaiReporter::class.java)

  override fun reportNoChoices() {
    logger.warn("OpenAI response contained no choices")
  }

  override fun reportMultipleChoices(choiceCount: Int) {
    logger.warn("OpenAI response contained multiple choices ($choiceCount); using the first")
  }

  override fun reportMissingTokenUsage() {
    logger.warn("OpenAI response did not include token usage")
  }

  override fun reportEmptyResponse() {
    logger.warn("OpenAI response choice contained neither content nor tool calls")
  }

  override fun reportUnknownFinishReason(finishReason: String) {
    logger.warn("OpenAI reported an unrecognized finish reason: $finishReason")
  }

  override fun reportInvalidToolName(rawToolName: String, cause: Throwable) {
    logger.warn("OpenAI returned an invalid tool name: $rawToolName", cause)
  }

  override fun reportMalformedToolCallArguments(rawArguments: String, cause: Throwable) {
    logger.warn("OpenAI returned malformed tool-call arguments: $rawArguments", cause)
  }

  override fun reportIoError(cause: Throwable) {
    logger.warn("Network I/O error during chat completion", cause)
  }

  override fun reportClientError(cause: Throwable) {
    logger.warn("OpenAI client error during chat completion", cause)
  }
}
