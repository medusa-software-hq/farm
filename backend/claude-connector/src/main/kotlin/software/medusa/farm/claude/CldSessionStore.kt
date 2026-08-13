package software.medusa.farm.claude

import java.nio.file.Path

/**
 * Owns where a `claude` run persists its session state so that state can be carried across runs
 * (and across workers). This is the one place coupled to the CLI's on-disk layout; keeping it
 * behind an interface isolates that coupling.
 *
 * Usage around a run: [prepare] the `HOME` the agent should run under, run the agent, then
 * [snapshot] that `HOME` into a resumable [CldSessionRef].
 */
interface CldSessionStore {
  /**
   * Provisions and returns the `HOME` directory for [session]. For [CldSessionSelector.Resume] the
   * captured snapshot is restored into it first, so a subsequent `--resume` finds the transcript.
   */
  fun prepare(session: CldSessionSelector): Path

  /** Captures the session state under [home] into a snapshot keyed by [sessionId]. */
  fun snapshot(sessionId: String, home: Path): CldSessionRef
}
