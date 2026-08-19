package software.medusa.farm.worker

import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiFreeClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.farm.shared.AgentRunLog

/**
 * Distills what a coding-agent run did into a short, dense summary — the orientation a follow-up
 * fixup run gets in place of the raw log (the agent re-reads the repo itself).
 */
interface RunSummarizer {
  /**
   * Summarizes [log].
   *
   * @return The summary of the run [log] describes.
   * @throws RunSummaryGenerationError If no summary could be had, for any reason.
   */
  suspend fun summarize(log: AgentRunLog): RunSummary

  companion object {
    /** Builds a summarizer backed by DeepSeek over OpenRouter, keyed by [openRouterApiKey]. */
    fun from(openRouterApiKey: String): RunSummarizer {
      val client =
          OaiProperClient.targeting(
                  targetBaseUrl = OaiFreeClient.openRouterBaseUrl,
                  targetApiKey = OaiApiKey(openRouterApiKey),
                  reporter = LoggingOaiReporter(),
              )
              .configured(model = OaiModel.DeepSeekFlash)
      return ProperRunSummarizer(client)
    }
  }
}
