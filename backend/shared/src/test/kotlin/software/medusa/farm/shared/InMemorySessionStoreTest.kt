package software.medusa.farm.shared

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class InMemorySessionStoreTest {
  private val store = InMemorySessionStore(Clock.systemUTC())

  @Test
  fun `a try is running until it is finished`(): Unit = runBlocking {
    openSession()
    store.startRunAttempt("s", ordinal = 0, attempt = 1)
    store.appendRunEntry("s", ordinal = 0, attempt = 1, position = 0, entry = step("first"))

    val running = assertIs<SessionRunAttempt.Running>(soleAttempt())
    assertEquals(listOf<AgentRunEntry>(step("first")), running.log.entries)

    store.finishRunAttempt(
        "s",
        ordinal = 0,
        attempt = 1,
        outcome = AgentRunOutcome.SUCCEEDED,
        cost = null,
        summary = "done",
    )

    val finished = assertIs<SessionRunAttempt.Finished>(soleAttempt())
    assertEquals(listOf<AgentRunEntry>(step("first")), finished.log.entries)
    assertEquals("done", finished.summary)
  }

  @Test
  fun `writing an entry twice leaves the log as it was`(): Unit = runBlocking {
    openSession()
    store.startRunAttempt("s", ordinal = 0, attempt = 1)
    store.appendRunEntry("s", ordinal = 0, attempt = 1, position = 0, entry = step("first"))
    store.appendRunEntry("s", ordinal = 0, attempt = 1, position = 0, entry = step("first"))

    assertEquals(listOf<AgentRunEntry>(step("first")), soleAttempt().log.entries)
  }

  @Test
  fun `a try that crashed is kept, and reads as abandoned once another follows it`(): Unit =
      runBlocking {
        openSession()
        store.startRunAttempt("s", ordinal = 0, attempt = 1)
        store.appendRunEntry("s", ordinal = 0, attempt = 1, position = 0, entry = step("got here"))

        // Nothing closes try 1 — it crashed. The retry is a try of its own, and what the first one
        // got to has to still be there to read.
        store.startRunAttempt("s", ordinal = 0, attempt = 2)
        store.appendRunEntry("s", ordinal = 0, attempt = 2, position = 0, entry = step("restarted"))

        val attempts = store.getRuns("s").single().attempts
        assertEquals(listOf(1, 2), attempts.map { it.number })

        val crashed = assertIs<SessionRunAttempt.Abandoned>(attempts.first())
        assertEquals(listOf<AgentRunEntry>(step("got here")), crashed.log.entries)

        val current = assertIs<SessionRunAttempt.Running>(attempts.last())
        assertEquals(listOf<AgentRunEntry>(step("restarted")), current.log.entries)
      }

  @Test
  fun `running the same try again starts it over without touching the ones before it`(): Unit =
      runBlocking {
        openSession()
        store.startRunAttempt("s", ordinal = 0, attempt = 1)
        store.appendRunEntry("s", ordinal = 0, attempt = 1, position = 0, entry = step("first try"))
        store.startRunAttempt("s", ordinal = 0, attempt = 2)
        store.appendRunEntry("s", ordinal = 0, attempt = 2, position = 0, entry = step("a"))
        store.appendRunEntry("s", ordinal = 0, attempt = 2, position = 1, entry = step("b"))

        // Try 2 runs again. It starts clean — a shorter run of it must not inherit the tail of the
        // longer one — while try 1 is left exactly as it was.
        store.startRunAttempt("s", ordinal = 0, attempt = 2)
        store.appendRunEntry("s", ordinal = 0, attempt = 2, position = 0, entry = step("only this"))

        val attempts = store.getRuns("s").single().attempts
        assertEquals(listOf<AgentRunEntry>(step("first try")), attempts.first().log.entries)
        assertEquals(listOf<AgentRunEntry>(step("only this")), attempts.last().log.entries)
      }

  private suspend fun soleAttempt(): SessionRunAttempt =
      store.getRuns("s").single().attempts.single()

  private suspend fun openSession() =
      store.create(
          id = "s",
          installationId = 1L,
          githubRepoId = 2L,
          number = 3,
          repoFullName = "acme/one",
          title = "Fix it",
      )

  private fun step(text: String) = AgentStep(text = text, toolActions = emptyList())
}
