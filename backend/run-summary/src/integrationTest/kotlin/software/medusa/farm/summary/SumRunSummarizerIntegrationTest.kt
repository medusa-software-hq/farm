package software.medusa.farm.summary

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction

/**
 * Drives the real model (DeepSeek over OpenRouter) to validate the openai-client wiring. Skips
 * itself unless a usable OPENROUTER_API_KEY is provisioned — the check is free but paid to run.
 * Skipping here is a property of the test harness, not of the summarizer: the worker requires the
 * key and will not start without it.
 */
class SumRunSummarizerIntegrationTest {
  @Test
  fun `summarizes a run log`() {
    val key = System.getenv("OPENROUTER_API_KEY")
    assumeTrue(!key.isNullOrBlank(), "no OPENROUTER_API_KEY")

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
}
