package software.medusa.farm.claude

import java.nio.file.Path

/**
 * A resumable handle to a finished run: the CLI [sessionId] plus the [snapshot] archive of its
 * persisted state. Restoring the snapshot into a fresh `HOME` and resuming [sessionId] lets a later
 * run continue with the agent's full prior context.
 */
data class CldSessionRef(
    val sessionId: String,
    val snapshot: Path,
)
