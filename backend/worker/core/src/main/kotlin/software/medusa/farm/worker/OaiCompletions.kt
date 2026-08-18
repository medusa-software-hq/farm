package software.medusa.farm.worker

import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult

/**
 * Maps the coarse [OaiResult]/[OaiResponse] tree to a [RunSummary]. Every anomaly the client folds
 * into that tree is already handed to the configured
 * [software.medusa.commons.openai_client.OaiReporter] — see [LoggingOaiReporter] — so here they
 * become a [RunSummaryError], never an empty summary. An interrupted (partial) answer still counts,
 * as long as it carried text.
 *
 * @throws RunSummaryBackendUnreachableError If the backend could not be reached, or answered with
 *   an error.
 * @throws RunSummaryEmptyAnswerError If the answer carried no usable text.
 */
internal fun OaiResult<OaiResponse>.toRunSummary(): RunSummary {
  val text =
      when (this) {
        OaiResult.NetworkError -> throw RunSummaryBackendUnreachableError
        is OaiResult.ResponseReceived ->
            when (val received = response) {
              is OaiResponse.Complete ->
                  when (val content = received.generatedContent) {
                    is OaiGeneratedContent.Full -> content.generatedMessage.content
                    is OaiGeneratedContent.Partial -> content.partialGeneratedText
                  }
              OaiResponse.Corrupted -> throw RunSummaryEmptyAnswerError
              is OaiResponse.Error -> throw RunSummaryBackendUnreachableError
            }
      }

  if (text.isNullOrBlank()) throw RunSummaryEmptyAnswerError

  return RunSummary(text = text)
}
