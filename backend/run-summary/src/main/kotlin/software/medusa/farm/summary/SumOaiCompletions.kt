package software.medusa.farm.summary

import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult

/**
 * Maps the coarse [OaiResult]/[OaiResponse] tree to a [RunSummary]. Every anomaly the client folds
 * into this tree (network failure, corrupted/empty/error response) is already handed to the
 * configured [software.medusa.commons.openai_client.OaiReporter] — see [SumLoggingOaiReporter] — so
 * here they become [RunSummary.Unavailable], never an empty summary. Only a complete response with
 * non-blank text is [RunSummary.Available]; an interrupted (partial) response yields its
 * best-available text when non-blank.
 */
internal fun OaiResult<OaiResponse>.toRunSummary(): RunSummary {
  val text =
      when (this) {
        OaiResult.NetworkError -> null
        is OaiResult.ResponseReceived ->
            when (val received = response) {
              is OaiResponse.Complete ->
                  when (val content = received.generatedContent) {
                    is OaiGeneratedContent.Full -> content.generatedMessage.content
                    is OaiGeneratedContent.Partial -> content.partialGeneratedText
                  }
              OaiResponse.Corrupted -> null
              is OaiResponse.Error -> null
            }
      }
  return if (text.isNullOrBlank()) RunSummary.Unavailable else RunSummary.Available(text)
}
