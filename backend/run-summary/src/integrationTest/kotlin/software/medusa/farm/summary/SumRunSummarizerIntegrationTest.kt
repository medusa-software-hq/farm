package software.medusa.farm.summary

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

/**
 * Drives the real model (DeepSeek over OpenRouter) to validate the openai-client wiring.
 *
 * Requires OPENROUTER_API_KEY, and fails without it rather than skipping: this suite is outside the
 * `check` lifecycle, so it only runs when someone asked for it, and a suite that reports green
 * having proven nothing is worse than one that reports red.
 */
class SumRunSummarizerIntegrationTest {
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

    val summary = runBlocking { SumRunSummarizer.from(key).summarize(log) }
    assertTrue(summary.text.isNotBlank(), "expected a non-empty summary")
  }

  private fun openRouterApiKey(): String {
    val key = System.getenv("OPENROUTER_API_KEY")
    check(!key.isNullOrBlank()) {
      "OPENROUTER_API_KEY is not set, so this suite cannot prove anything. It is not skipped for " +
          "you: a green check that ran nothing is how an unprovisioned key stays invisible."
    }
    return key
  }
}
