package software.medusa.farm.cli.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json

/**
 * All persisted per-environment state under a single [Environment.resolveConfigDirPath]. Today that
 * is just the cached sign-in ([Credentials]); this is the one place that reads or writes the config
 * directory, so its file layout and on-disk permissions live in exactly one spot.
 *
 * The refresh token inside [Credentials] is the sensitive bit — it stands in for the human until
 * revoked — so the file is written 0600 and the directory created 0700, set atomically at creation
 * where the platform supports POSIX permissions. [dir] is the environment's partitioned config
 * directory; there is no ambient default, so a prod and a staging session can never share a file.
 */
class ConfigStore(private val dir: Path) {
  private val credentialsFile: Path = dir.resolve(CREDENTIALS_FILE_NAME)

  fun loadCredentials(): Credentials? {
    if (!Files.exists(credentialsFile)) return null
    return json.decodeFromString(Files.readString(credentialsFile))
  }

  /** Writes [credentials] with dir 0700 / file 0600, set atomically at creation where supported. */
  fun saveCredentials(credentials: Credentials) {
    if (!Files.exists(dir)) {
      runCatching { Files.createDirectory(dir, DIR_PERMISSIONS) }
          .getOrElse { Files.createDirectories(dir) }
    }
    Files.deleteIfExists(credentialsFile)
    runCatching { Files.createFile(credentialsFile, FILE_PERMISSIONS) }
        .getOrElse { Files.createFile(credentialsFile) }
    Files.writeString(credentialsFile, json.encodeToString(credentials))
  }

  fun deleteCredentials() {
    Files.deleteIfExists(credentialsFile)
  }

  companion object {
    private const val CREDENTIALS_FILE_NAME = "credentials.json"

    private val json = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
    }

    /** Owner-only directory (0700) and file (0600) attributes, applied atomically at creation. */
    private val DIR_PERMISSIONS =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val FILE_PERMISSIONS =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
  }
}
