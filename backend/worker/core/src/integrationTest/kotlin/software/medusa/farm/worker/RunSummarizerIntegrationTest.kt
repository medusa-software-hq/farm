package software.medusa.farm.worker

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

/** Drives the real model (DeepSeek over OpenRouter) to validate the openai-client wiring. */
class RunSummarizerIntegrationTest {
  @Test
  fun `summarizes a run log`() {
    val key = openRouterApiKey()

    val log =
        AgentRunLog(
            steps =
                listOf(
                    AgentStep(
                        text = "I'll fix the off-by-one in the fibonacci base case.",
                        toolActions =
                            listOf(
                                AgentToolAction.EditFile("backend/worker/Fibonacci.kt"),
                                AgentToolAction.RunCommand("./gradlew :backend:worker:test"),
                            ),
                    )
                )
        )

    val summary = runBlocking { RunSummarizer.from(key).summarize(log) }
    assertTrue(summary.text.isNotBlank(), "expected a non-empty summary")
  }

  private fun openRouterApiKey(): String {
    val key = System.getenv("OPENROUTER_API_KEY")
    check(!key.isNullOrBlank()) { "OPENROUTER_API_KEY is not set" }
    return key
  }
}
