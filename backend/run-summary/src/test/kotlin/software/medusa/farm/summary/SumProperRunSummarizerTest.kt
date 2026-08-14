package software.medusa.farm.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult
import software.medusa.commons.openai_client.OaiTokenUsage
import software.medusa.commons.openai_client.messages.OaiAssistantMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage

class SumProperRunSummarizerTest {
  @Test
  fun `returns the model's assistant text`() = runBlocking {
    val client = CapturingClient(reply = "- fixed the base case")
    val summary = SumProperRunSummarizer(client).summarize("edited Fibonacci.kt; ran tests")
    assertEquals("- fixed the base case", summary)
  }

  @Test
  fun `hands the run log to the model as a user message`() = runBlocking {
    val client = CapturingClient(reply = "ok")
    SumProperRunSummarizer(client).summarize("the run log")
    val userMessages = client.lastHistory!!.messages.filterIsInstance<OaiUserMessage>()
    assertTrue(
        userMessages.any { it.content == "the run log" },
        "run log not sent as a user message",
    )
  }

  /** Records the chat history it was called with and replies with a fixed assistant message. */
  private class CapturingClient(private val reply: String) : OaiConfiguredClient {
    var lastHistory: OaiChatHistory? = null

    override suspend fun completeChat(
        chatHistory: OaiChatHistory,
        inferenceParams: OaiInferenceParams,
    ): OaiResult<OaiResponse> {
      lastHistory = chatHistory
      return OaiResult.ResponseReceived(
          OaiResponse.Complete(
              OaiGeneratedContent.Full(
                  OaiAssistantMessage(content = reply, toolCalls = emptyList())
              ),
              OaiTokenUsage(0, 0, 0),
          )
      )
    }
  }
}
