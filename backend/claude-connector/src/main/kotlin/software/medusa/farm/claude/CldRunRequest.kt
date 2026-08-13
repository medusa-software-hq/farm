package software.medusa.farm.claude

import java.nio.file.Path

/**
 * One unit of agent work.
 *
 * @property workspace the directory the agent runs in and mutates; the caller diffs it afterwards.
 * @property home the `HOME` the run persists its session under (from [CldSessionStore.prepare]).
 * @property prompt the task text — the issue body on a fresh run, the review feedback on a fixup.
 * @property session start fresh or resume a prior snapshot.
 */
data class CldRunRequest(
    val workspace: Path,
    val home: Path,
    val prompt: String,
    val session: CldSessionSelector,
)
