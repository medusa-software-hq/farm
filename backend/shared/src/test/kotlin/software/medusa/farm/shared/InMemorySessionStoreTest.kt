package software.medusa.farm.shared

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class InMemorySessionStoreTest {
  private val store = InMemorySessionStore(Clock.systemUTC())

  @Test
  fun `a run is running until it is finished`(): Unit = runBlocking {
    openSession()
    store.startRun("s", ordinal = 0)
    store.appendRunEntry("s", ordinal = 0, position = 0, entry = step("first"))

    val running = assertIs<SessionRun.Running>(store.getRuns("s").single())
    assertEquals(listOf<AgentRunEntry>(step("first")), running.log.entries)

    store.finishRun("s", ordinal = 0, AgentRunOutcome.SUCCEEDED, cost = null, summary = "done")

    val finished = assertIs<SessionRun.Finished>(store.getRuns("s").single())
    assertEquals(listOf<AgentRunEntry>(step("first")), finished.log.entries)
    assertEquals("done", finished.summary)
  }

  @Test
  fun `writing an entry twice leaves the log as it was`(): Unit = runBlocking {
    openSession()
    store.startRun("s", ordinal = 0)
    store.appendRunEntry("s", ordinal = 0, position = 0, entry = step("first"))
    store.appendRunEntry("s", ordinal = 0, position = 0, entry = step("first"))

    assertEquals(listOf<AgentRunEntry>(step("first")), store.getRuns("s").single().log.entries)
  }

  @Test
  fun `reopening a run discards what the attempt before it recorded`(): Unit = runBlocking {
    openSession()
    store.startRun("s", ordinal = 0)
    store.appendRunEntry("s", ordinal = 0, position = 0, entry = step("from the first attempt"))
    store.appendRunEntry("s", ordinal = 0, position = 1, entry = step("also the first"))

    // A retried attempt starts over. A shorter second attempt must not leave the tail of a longer
    // first one behind, reading as though it had done work it never did.
    store.startRun("s", ordinal = 0)
    store.appendRunEntry("s", ordinal = 0, position = 0, entry = step("from the second attempt"))

    assertEquals(
        listOf<AgentRunEntry>(step("from the second attempt")),
        store.getRuns("s").single().log.entries,
    )
  }

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
