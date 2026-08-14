package software.medusa.farm.summary

import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage

internal class SumProperRunSummarizer(private val client: OaiConfiguredClient) : SumRunSummarizer {
  override suspend fun summarize(runLog: String): String {
    val history =
        OaiChatHistory(
            messages =
                listOf(
                    OaiSystemMessage(content = SYSTEM_PROMPT),
                    OaiUserMessage(content = runLog),
                ),
        )
    return client
        .completeChat(chatHistory = history, inferenceParams = INFERENCE_PARAMS)
        .extractAssistantText()
  }

  private companion object {
    val INFERENCE_PARAMS = OaiInferenceParams(maxOutputTokenCount = 700)

    val SYSTEM_PROMPT =
        """
        You are summarizing what an autonomous coding agent did in a single run on a code repository.
        You are given the agent's action log — its messages and tool calls. Produce a concise,
        information-dense summary of what it changed and the notable decisions or assumptions it
        made, to orient a follow-up run that will address review feedback.

        Capture intent and outcome, not a transcript: omit routine file reads and navigation, and do
        not restate the log verbatim. Prefer a short bulleted list.
        """
            .trimIndent()
  }
}
