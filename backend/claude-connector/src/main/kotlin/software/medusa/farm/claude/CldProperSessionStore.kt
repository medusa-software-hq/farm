package software.medusa.farm.claude

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/**
 * A filesystem [CldSessionStore]. Each session runs under `<root>/homes/<sessionId>`, and its
 * `.claude` subtree — the CLI's persisted session state — is snapshotted to a zip under
 * `<root>/snapshots`. Resuming restores that subtree before the run.
 *
 * Zip rather than the CLI's raw layout so a snapshot is a single self-describing artifact the
 * caller can hand to blob storage; only this class knows it is the `.claude` directory inside.
 */
class CldProperSessionStore(
    private val root: Path,
) : CldSessionStore {
  override fun prepare(session: CldSessionSelector): Path {
    val home = homeDir(session.sessionId)
    Files.createDirectories(home.resolve(claudeDirName))
    if (session is CldSessionSelector.Resume) {
      unzipInto(session.from.snapshot, home)
    }
    return home
  }

  override fun snapshot(sessionId: String, home: Path): CldSessionRef {
    val archive = root.resolve(snapshotsDir).resolve("$sessionId.zip")
    archive.createParentDirectories()
    zipClaudeSubtree(home, archive)
    return CldSessionRef(sessionId = sessionId, snapshot = archive)
  }

  private fun homeDir(sessionId: String): Path = root.resolve(homesDir).resolve(sessionId)

  /** Zips the `.claude` subtree of [home] into [archive], with entries relative to [home]. */
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  private fun zipClaudeSubtree(home: Path, archive: Path) {
    val claudeDir = home.resolve(claudeDirName)
    ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
      if (!claudeDir.exists()) return@use
      claudeDir
          .walk()
          .filter { it.isRegularFile() }
          .forEach { file ->
            val entryName = home.relativize(file).joinToString("/")
            zip.putNextEntry(ZipEntry(entryName))
            Files.copy(file, zip)
            zip.closeEntry()
          }
    }
  }

  /** Restores [archive]'s entries under [home], rejecting any that escape it (zip-slip guard). */
  private fun unzipInto(archive: Path, home: Path) {
    ZipInputStream(Files.newInputStream(archive)).use { zip ->
      var entry: ZipEntry? = zip.nextEntry
      while (entry != null) {
        val target = home.resolve(entry.name).normalize()
        if (!target.startsWith(home)) {
          throw IOException("Snapshot entry escapes the home directory: ${entry.name}")
        }
        target.createParentDirectories()
        Files.copy(zip, target)
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }
  }

  private companion object {
    const val claudeDirName = ".claude"
    const val homesDir = "homes"
    const val snapshotsDir = "snapshots"
  }
}
