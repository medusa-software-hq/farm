package software.medusa.farm.shared

import java.time.Instant

/**
 * A stored processing session. Self-describing: it records the issue it worked (repo + number +
 * title as they were at the time) so the sessions list and archive survive the issue closing or
 * being renamed. [finishedAt] is null while the session is still running.
 */
class Session(
    val id: String,
    val installationId: Long,
    val githubRepoId: Long,
    val number: Int,
    val repoFullName: String,
    val title: String,
    val state: SessionState,
    val startedAt: Instant,
    val finishedAt: Instant?,
)
