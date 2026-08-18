package software.medusa.farm.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

class SumProperRunSummarizerTest {
  private val log =
      AgentRunLog(
          steps =
              listOf(
                  AgentStep(
                      text = "fixing the base case",
                      toolActions =
                          listOf(
                              AgentToolAction.EditFile("src/A.kt"),
                              AgentToolAction.RunCommand("gradle test"),
                          ),
                  )
              )
      )

  @Test
  fun `a complete answer is the summary`(): Unit = runBlocking {
    val summary = SumProperRunSummarizer(reply("- fixed the base case")).summarize(log)
    assertEquals(RunSummary("- fixed the base case"), summary)
  }

  @Test
  fun `a backend that cannot be reached raises`() {
    assertFailsWith<SumBackendUnreachableError> {
      runBlocking { SumProperRunSummarizer(FailingClient).summarize(log) }
    }
  }

  @Test
  fun `a blank answer raises rather than passing for a summary`() {
    assertFailsWith<SumEmptyAnswerError> {
      runBlocking { SumProperRunSummarizer(reply("   ")).summarize(log) }
    }
  }

  @Test
  fun `an answer that could not be understood raises`() {
    assertFailsWith<SumEmptyAnswerError> {
      runBlocking { SumProperRunSummarizer(CorruptedClient).summarize(log) }
    }
  }

  @Test
  fun `the rendered log reaches the model with its actions`(): Unit = runBlocking {
    val client = reply("ok")
    SumProperRunSummarizer(client).summarize(log)
    val rendered = client.lastHistory!!.messages.filterIsInstance<OaiUserMessage>().single().content
    assertIs<String>(rendered)
    assertTrue(rendered.contains("edited src/A.kt"), "missing the edit action")
    assertTrue(rendered.contains("ran: gradle test"), "missing the command action")
  }

  private fun reply(text: String) = CapturingClient(text)

  /** Records the chat history and replies with a fixed complete assistant message. */
  private class CapturingClient(private val text: String) : OaiConfiguredClient {
    var lastHistory: OaiChatHistory? = null

    override suspend fun completeChat(
        chatHistory: OaiChatHistory,
        inferenceParams: OaiInferenceParams,
    ): OaiResult<OaiResponse> {
      lastHistory = chatHistory
      return OaiResult.ResponseReceived(
          OaiResponse.Complete(
              OaiGeneratedContent.Full(
                  OaiAssistantMessage(content = text, toolCalls = emptyList())
              ),
              OaiTokenUsage(0, 0, 0),
          )
      )
    }
  }

  private object CorruptedClient : OaiConfiguredClient {
    override suspend fun completeChat(
        chatHistory: OaiChatHistory,
        inferenceParams: OaiInferenceParams,
    ): OaiResult<OaiResponse> = OaiResult.ResponseReceived(OaiResponse.Corrupted)
  }

  private object FailingClient : OaiConfiguredClient {
    override suspend fun completeChat(
        chatHistory: OaiChatHistory,
        inferenceParams: OaiInferenceParams,
    ): OaiResult<OaiResponse> = OaiResult.NetworkError
  }
}
