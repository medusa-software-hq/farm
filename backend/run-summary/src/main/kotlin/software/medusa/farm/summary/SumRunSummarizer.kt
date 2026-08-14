package software.medusa.farm.summary

import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiFreeClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.farm.shared.AgentRunLog

/**
 * Distills what a coding-agent run did into a short, dense summary — the orientation a follow-up
 * fixup run gets in place of the raw log (the agent re-reads the repo itself). Takes the app's own
 * [AgentRunLog], not a string; returns a typed [RunSummary].
 */
interface SumRunSummarizer {
  suspend fun summarize(log: AgentRunLog): RunSummary

  companion object {
    private const val API_KEY_ENV_VAR_NAME = "OPENROUTER_API_KEY"

    /** Builds a summarizer backed by DeepSeek over OpenRouter, keyed by `OPENROUTER_API_KEY`. */
    fun fromEnv(getenv: (String) -> String?): SumRunSummarizer {
      val apiKey =
          getenv(API_KEY_ENV_VAR_NAME)
              ?: error("$API_KEY_ENV_VAR_NAME environment variable must be set")
      val client =
          OaiProperClient.targeting(
                  targetBaseUrl = OaiFreeClient.openRouterBaseUrl,
                  targetApiKey = OaiApiKey(apiKey),
                  reporter = SumLoggingOaiReporter(),
              )
              .configured(model = OaiModel.DeepSeekFlash)
      return SumProperRunSummarizer(client)
    }
  }
}
