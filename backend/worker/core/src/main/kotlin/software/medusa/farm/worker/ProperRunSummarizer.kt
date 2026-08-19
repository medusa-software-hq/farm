package software.medusa.farm.worker

import java.util.logging.Logger
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentToolAction

internal class ProperRunSummarizer(private val client: OaiConfiguredClient) : RunSummarizer {
  private val logger: Logger = Logger.getLogger(loggerName)

  override suspend fun summarize(log: AgentRunLog): RunSummary {
    val history =
        OaiChatHistory(
            messages =
                listOf(
                    OaiSystemMessage(content = SYSTEM_PROMPT),
                    OaiUserMessage(content = render(log)),
                ),
        )
    val result = client.completeChat(chatHistory = history, inferenceParams = INFERENCE_PARAMS)

    val text =
        when (result) {
          OaiResult.NetworkError -> unavailable("the backend could not be reached")
          is OaiResult.ResponseReceived ->
              when (val received = result.response) {
                is OaiResponse.Complete ->
                    when (val content = received.generatedContent) {
                      is OaiGeneratedContent.Full -> content.generatedMessage.content
                      // An interrupted answer is a truncated summary, which is a wrong one — it
                      // would orient the next run with a description that stops mid-thought.
                      is OaiGeneratedContent.Partial ->
                          unavailable("the answer was interrupted (${content.interruptionReason})")
                    }
                OaiResponse.Corrupted -> unavailable("the answer could not be understood")
                is OaiResponse.Error -> unavailable("the backend answered with an error")
              }
        }

    if (text.isNullOrBlank()) unavailable("the answer carried no text")

    return RunSummary(text = text)
  }

  /**
   * Records why no summary could be had, then raises. Every path to [RunSummaryGenerationError]
   * goes through here, so a failure is never silent even though the error itself carries no detail.
   */
  private fun unavailable(reason: String): Nothing {
    logger.warning("No run summary: $reason")
    throw RunSummaryGenerationError
  }

  /** Renders the action log to the plain text the model reads (the one place a String is apt). */
  private fun render(log: AgentRunLog): String =
      log.steps.joinToString(separator = "\n\n") { step ->
        val lines = buildList {
          if (step.text.isNotBlank()) add(step.text.trim())
          step.toolActions.forEach { add("- ${describe(it)}") }
        }
        lines.joinToString(separator = "\n")
      }

  private fun describe(action: AgentToolAction): String =
      when (action) {
        is AgentToolAction.EditFile -> "edited ${action.path}"
        is AgentToolAction.ReadFile -> "read ${action.path}"
        is AgentToolAction.RunCommand -> "ran: ${action.command}"
        is AgentToolAction.Search -> "searched: ${action.query}"
        is AgentToolAction.Other -> "used ${action.tool}"
      }

  private companion object {
    const val loggerName = "software.medusa.farm.worker.summary"

    val INFERENCE_PARAMS = OaiInferenceParams(maxOutputTokenCount = 700)

    val SYSTEM_PROMPT =
        """
        You are summarizing what an autonomous coding agent did in a single run on a code repository.
        You are given the agent's actions — its messages and the file edits, commands, and searches
        it ran. Produce a concise, information-dense summary of what it changed and the notable
        decisions or assumptions it made, to orient a follow-up run that will address review feedback.

        Capture intent and outcome, not a transcript. Prefer a short bulleted list.
        """
            .trimIndent()
  }
}
