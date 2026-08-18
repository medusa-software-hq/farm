package software.medusa.farm.summary

import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiFreeClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.farm.shared.AgentRunLog

/**
 * Distills what a coding-agent run did into a short, dense summary — the orientation a follow-up
 * fixup run gets in place of the raw log (the agent re-reads the repo itself).
 */
interface SumRunSummarizer {
  /**
   * Summarizes [log].
   *
   * @return The summary of the run [log] describes.
   * @throws SumBackendUnreachableError If the backend could not be reached, or answered with an
   *   error.
   * @throws SumEmptyAnswerError If the answer carried no usable text.
   */
  suspend fun summarize(log: AgentRunLog): RunSummary

  companion object {
    /** Builds a summarizer backed by DeepSeek over OpenRouter, keyed by [openRouterApiKey]. */
    fun from(openRouterApiKey: String): SumRunSummarizer {
      val client =
          OaiProperClient.targeting(
                  targetBaseUrl = OaiFreeClient.openRouterBaseUrl,
                  targetApiKey = OaiApiKey(openRouterApiKey),
                  reporter = SumLoggingOaiReporter(),
              )
              .configured(model = OaiModel.DeepSeekFlash)
      return SumProperRunSummarizer(client)
    }
  }
}
