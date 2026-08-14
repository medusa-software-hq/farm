package software.medusa.farm.summary

import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult

/**
 * Collapses the coarse [OaiResult]/[OaiResponse] tree to the assistant's text. Every anomaly the
 * client folds into this tree (network failure, corrupted/empty/error response) is already handed
 * to the configured [software.medusa.commons.openai_client.OaiReporter] — see
 * [SumLoggingOaiReporter] — so here they simply map to an empty summary. An interrupted (partial)
 * response yields its best-available text.
 */
internal fun OaiResult<OaiResponse>.extractAssistantText(): String =
    when (this) {
      OaiResult.NetworkError -> ""
      is OaiResult.ResponseReceived ->
          when (val received = response) {
            is OaiResponse.Complete ->
                when (val content = received.generatedContent) {
                  is OaiGeneratedContent.Full -> content.generatedMessage.content.orEmpty()
                  is OaiGeneratedContent.Partial -> content.partialGeneratedText.orEmpty()
                }
            OaiResponse.Corrupted -> ""
            is OaiResponse.Error -> ""
          }
    }
