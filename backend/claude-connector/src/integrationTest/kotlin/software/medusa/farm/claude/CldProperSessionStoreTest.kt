package software.medusa.farm.claude

import java.nio.file.Files
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CldProperSessionStoreTest {
  @Test
  fun `prepare provisions a home with a claude directory`() {
    val store = CldProperSessionStore(Files.createTempDirectory("cld-store"))

    val home = store.prepare(CldSessionSelector.Fresh("sess-1"))

    assertTrue(home.resolve(".claude").exists())
  }

  @Test
  fun `a snapshot restores the session state onto another worker`() {
    // Two stores with distinct roots stand in for two workers sharing only the snapshot artifact.
    val firstWorker = CldProperSessionStore(Files.createTempDirectory("cld-worker-1"))
    val secondWorker = CldProperSessionStore(Files.createTempDirectory("cld-worker-2"))

    // Worker 1: a session writes some transcript state under HOME/.claude, then snapshots it.
    val firstHome = firstWorker.prepare(CldSessionSelector.Fresh("sess-1"))
    val transcript = firstHome.resolve(".claude/projects/-work-repo/sess-1.jsonl")
    transcript.createParentDirectories()
    transcript.writeText("""{"type":"user"}""")
    val ref = firstWorker.snapshot("sess-1", firstHome)

    // Worker 2: resuming from the snapshot rehydrates that state under its own HOME.
    val secondHome = secondWorker.prepare(CldSessionSelector.Resume(ref))

    val restored = secondHome.resolve(".claude/projects/-work-repo/sess-1.jsonl")
    assertTrue(restored.exists())
    assertEquals("""{"type":"user"}""", restored.readText())
    assertTrue(firstHome != secondHome)
  }

  @Test
  fun `snapshotting a home with no session state yields an empty, restorable archive`() {
    val store = CldProperSessionStore(Files.createTempDirectory("cld-store"))
    val home = store.prepare(CldSessionSelector.Fresh("sess-empty"))

    val ref = store.snapshot("sess-empty", home)

    assertTrue(ref.snapshot.exists())
    // Restoring an empty snapshot still yields a usable home.
    val restored = store.prepare(CldSessionSelector.Resume(ref))
    assertTrue(restored.resolve(".claude").exists())
  }
}
