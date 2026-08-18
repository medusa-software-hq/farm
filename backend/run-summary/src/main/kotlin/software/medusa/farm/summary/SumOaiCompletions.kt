package software.medusa.farm.summary

import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult

/**
 * Maps the coarse [OaiResult]/[OaiResponse] tree to a [RunSummary]. Every anomaly the client folds
 * into that tree is already handed to the configured
 * [software.medusa.commons.openai_client.OaiReporter] — see [SumLoggingOaiReporter] — so here they
 * become a [SumError], never an empty summary. An interrupted (partial) answer still counts, as
 * long as it carried text.
 *
 * @throws SumBackendUnreachableError If the backend could not be reached, or answered with an
 *   error.
 * @throws SumEmptyAnswerError If the answer carried no usable text.
 */
internal fun OaiResult<OaiResponse>.toRunSummary(): RunSummary {
  val text =
      when (this) {
        OaiResult.NetworkError -> throw SumBackendUnreachableError
        is OaiResult.ResponseReceived ->
            when (val received = response) {
              is OaiResponse.Complete ->
                  when (val content = received.generatedContent) {
                    is OaiGeneratedContent.Full -> content.generatedMessage.content
                    is OaiGeneratedContent.Partial -> content.partialGeneratedText
                  }
              OaiResponse.Corrupted -> throw SumEmptyAnswerError
              is OaiResponse.Error -> throw SumBackendUnreachableError
            }
      }

  if (text.isNullOrBlank()) throw SumEmptyAnswerError

  return RunSummary(text = text)
}
