package software.medusa.farm.summary

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Drives the real model (DeepSeek over OpenRouter) to validate the openai-client wiring. Skips
 * itself unless a usable OPENROUTER_API_KEY is provisioned — the check is free but paid to run.
 */
class SumRunSummarizerIntegrationTest {
  @Test
  fun `summarizes a run log into non-empty text`() {
    val key = System.getenv("OPENROUTER_API_KEY")
    assumeTrue(!key.isNullOrBlank(), "no OPENROUTER_API_KEY")

    val summarizer = SumRunSummarizer.fromEnv(System::getenv)
    val runLog =
        """
        [assistant] I'll fix the off-by-one in the fibonacci base case.
        [tool: edit] backend/worker/Fibonacci.kt — changed `n <= 0` to `n < 0`.
        [tool: bash] ./gradlew :backend:worker:test — BUILD SUCCESSFUL.
        [assistant] Done: corrected the base case and the tests pass.
        """
            .trimIndent()

    val summary = runBlocking { summarizer.summarize(runLog) }
    assertTrue(summary.isNotBlank(), "expected a non-empty summary")
  }
}
