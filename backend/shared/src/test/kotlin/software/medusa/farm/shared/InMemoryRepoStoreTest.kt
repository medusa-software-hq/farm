package software.medusa.farm.shared

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class InMemoryRepoStoreTest {
  private val clock = MutableClock(Instant.parse("2020-01-01T00:00:00Z"))
  private val store = InMemoryRepoStore(clock)

  private val installation = 100L

  private fun fetched(id: Long, fullName: String) =
      FetchedRepo(
          githubRepoId = id,
          fullName = fullName,
          name = fullName.substringAfter('/'),
          isPrivate = false,
          defaultBranch = "main",
      )

  private fun syncNow(): Instant = clock.instant()

  private fun activeFullNames(): List<String> = runBlocking {
    store.listActive(installation).map { it.fullName }
  }

  @Test
  fun `adds fetched repos`() = runBlocking {
    store.reconcile(installation, listOf(fetched(1, "acme/a"), fetched(2, "acme/b")), syncNow())
    assertEquals(listOf("acme/a", "acme/b"), activeFullNames())
  }

  @Test
  fun `updates a repo's mutable fields on the next sync`() = runBlocking {
    store.reconcile(installation, listOf(fetched(1, "acme/old")), syncNow())
    clock.advanceMinutes(1)
    store.reconcile(installation, listOf(fetched(1, "acme/renamed")), syncNow())

    val repos = store.listActive(installation)
    assertEquals(1, repos.size)
    assertEquals("acme/renamed", repos.single().fullName)
  }

  @Test
  fun `orphans a repo that is gone on the next sync`() = runBlocking {
    store.reconcile(installation, listOf(fetched(1, "acme/a"), fetched(2, "acme/b")), syncNow())
    clock.advanceMinutes(1)
    store.reconcile(installation, listOf(fetched(1, "acme/a")), syncNow())

    assertEquals(listOf("acme/a"), activeFullNames())
  }

  @Test
  fun `reactivates a returning repo`() = runBlocking {
    store.reconcile(installation, listOf(fetched(1, "acme/a"), fetched(2, "acme/b")), syncNow())
    clock.advanceMinutes(1)
    store.reconcile(installation, listOf(fetched(1, "acme/a")), syncNow()) // orphans #2
    assertEquals(listOf("acme/a"), activeFullNames())

    clock.advanceMinutes(1)
    store.reconcile(installation, listOf(fetched(1, "acme/a"), fetched(2, "acme/b")), syncNow())
    assertEquals(listOf("acme/a", "acme/b"), activeFullNames())
  }

  @Test
  fun `reconciling the same set again orphans nothing`() = runBlocking {
    val repos = listOf(fetched(1, "acme/a"), fetched(2, "acme/b"))
    store.reconcile(installation, repos, syncNow())
    clock.advanceMinutes(1)
    store.reconcile(installation, repos, syncNow())

    assertEquals(listOf("acme/a", "acme/b"), activeFullNames())
  }

  @Test
  fun `orphaning is scoped to the syncing installation`() = runBlocking {
    val other = 200L
    store.reconcile(installation, listOf(fetched(1, "acme/a")), syncNow())
    store.reconcile(other, listOf(fetched(9, "other/z")), syncNow())

    clock.advanceMinutes(1)
    // A sync for `installation` that no longer sees its repo must not touch `other`'s rows.
    store.reconcile(installation, emptyList(), syncNow())

    assertEquals(emptyList(), activeFullNames())
    assertEquals(listOf("other/z"), runBlocking { store.listActive(other).map { it.fullName } })
  }
}
