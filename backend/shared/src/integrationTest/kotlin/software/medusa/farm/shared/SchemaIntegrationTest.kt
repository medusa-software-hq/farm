package software.medusa.farm.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Drives the real migrations and the real stores against a real Postgres.
 *
 * The `.sq` files carry a hand-copied mirror of the schema, and SQLDelight checks the queries
 * against that copy rather than against what Flyway builds — so a mirror that has drifted still
 * compiles, and only fails when someone runs the query. Running the generated queries against a
 * migrated database is what makes the drift impossible to miss.
 */
class SchemaIntegrationTest {
  @Test
  fun `every migration applies to an empty database`() {
    withDatabase { url ->
      // A second call finds nothing left to do, which a migration that is not idempotent, or a
      // duplicate version Flyway refuses, would not survive.
      assertTrue(FarmStore.migrate(url) > 0, "no migrations were applied")
      assertEquals(0, FarmStore.migrate(url), "migrating an up-to-date database did something")
    }
  }

  @Test
  fun `a session and its runs survive a round trip through the built schema`() {
    withDatabase { url ->
      FarmStore.migrate(url)
      val sessions = FarmStore.build(url).session

      runBlocking {
        sessions.create(
            id = "s",
            installationId = 1L,
            githubRepoId = 2L,
            number = 3,
            repoFullName = "acme/one",
            title = "Fix it",
        )
        sessions.recordPullRequest(
            "s",
            number = 12,
            url = "https://example.test/pr/12",
            headSha = "abc",
        )

        // Try 1 crashes without closing; try 2 does the work. Both have to come back.
        sessions.startRunAttempt("s", ordinal = 0, attempt = 1)
        sessions.appendRunEntry("s", 0, attempt = 1, position = 0, entry = AgentWarning("careful"))

        sessions.startRunAttempt("s", ordinal = 0, attempt = 2)
        sessions.appendRunEntry(
            "s",
            0,
            attempt = 2,
            position = 0,
            entry = AgentStep("fixed it", listOf(AgentToolAction.EditFile("src/A.kt"))),
        )
        sessions.finishRunAttempt(
            "s",
            ordinal = 0,
            attempt = 2,
            outcome = AgentRunOutcome.SUCCEEDED,
            cost = AgentRunCost(usd = 0.12, turns = 2, durationMs = 345),
            summary = "a summary",
        )

        val run = sessions.getRuns("s").single()
        assertEquals(listOf(1, 2), run.attempts.map { it.number })

        val crashed = assertIs<SessionRunAttempt.Abandoned>(run.attempts.first())
        assertEquals(listOf<AgentRunEntry>(AgentWarning("careful")), crashed.log.entries)

        val finished = assertIs<SessionRunAttempt.Finished>(run.attempts.last())
        assertEquals(AgentRunOutcome.SUCCEEDED, finished.outcome)
        assertEquals(AgentRunCost(usd = 0.12, turns = 2, durationMs = 345), finished.cost)
        assertEquals(
            listOf<AgentRunEntry>(
                AgentStep("fixed it", listOf(AgentToolAction.EditFile("src/A.kt")))
            ),
            finished.log.entries,
        )

        assertEquals("https://example.test/pr/12", sessions.get("s")?.pullRequest?.url)
      }
    }
  }

  @Test
  fun `reopening a try clears only its own entries`() {
    withDatabase { url ->
      FarmStore.migrate(url)
      val sessions = FarmStore.build(url).session

      runBlocking {
        sessions.create("s", 1L, 2L, 3, "acme/one", "Fix it")
        sessions.startRunAttempt("s", ordinal = 0, attempt = 1)
        sessions.appendRunEntry(
            "s",
            0,
            1,
            position = 0,
            entry = AgentStep("first try", emptyList()),
        )
        sessions.startRunAttempt("s", ordinal = 0, attempt = 2)
        sessions.appendRunEntry("s", 0, 2, position = 0, entry = AgentStep("a", emptyList()))
        sessions.appendRunEntry("s", 0, 2, position = 1, entry = AgentStep("b", emptyList()))

        // The delete is a real cascade here, not a map being rebuilt in memory.
        sessions.startRunAttempt("s", ordinal = 0, attempt = 2)
        sessions.appendRunEntry(
            "s",
            0,
            2,
            position = 0,
            entry = AgentStep("only this", emptyList()),
        )

        val attempts = sessions.getRuns("s").single().attempts
        assertEquals(
            listOf<AgentRunEntry>(AgentStep("first try", emptyList())),
            attempts.first().log.entries,
        )
        assertEquals(
            listOf<AgentRunEntry>(AgentStep("only this", emptyList())),
            attempts.last().log.entries,
        )
      }
    }
  }

  /** One throwaway database per test, so nothing one leaves behind can prop another one up. */
  private fun withDatabase(block: (String) -> Unit) {
    PostgreSQLContainer("postgres:16-alpine").use { container ->
      container.start()
      // The store takes credentials in the URL and nothing else, and the container's URL already
      // carries query parameters of its own.
      val separator = if ("?" in container.jdbcUrl) "&" else "?"
      block(
          "${container.jdbcUrl}${separator}user=${container.username}" +
              "&password=${container.password}"
      )
    }
  }
}
