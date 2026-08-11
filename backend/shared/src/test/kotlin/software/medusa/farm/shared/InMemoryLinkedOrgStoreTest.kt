package software.medusa.farm.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class InMemoryLinkedOrgStoreTest {
  @Test
  fun `links, lists, and looks up by org`() = runBlocking {
    val store = InMemoryLinkedOrgStore()
    store.link(20L, "zebra")
    store.link(10L, "acme")

    assertEquals(listOf("acme", "zebra"), store.list().map { it.orgLogin })
    assertEquals(10L, store.getByOrg("acme")?.installationId)
    assertNull(store.getByOrg("missing"))
  }

  @Test
  fun `link is idempotent on installation id`() = runBlocking {
    val store = InMemoryLinkedOrgStore()
    store.link(10L, "acme")
    store.link(10L, "acme")

    assertEquals(1, store.list().size)
  }
}
