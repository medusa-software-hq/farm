package software.medusa.farm.claude

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/**
 * A live handle to one running `claude` invocation, in the shape of the commons `SysProcessHandle`
 * it sits on: collect [steps] to observe the agent's actions as they stream, await [result] for the
 * terminal outcome, and [close] when done. `use { }` is the intended pattern — [close] cancels the
 * reader and kills the process tree, so a leaked handle leaks a process.
 */
interface CldRun : AutoCloseable {
  /** What the opening `init` handshake reported — available now, not at the end of the run. */
  val info: CldRunInfo

  /** The agent's steps as they stream; completes when the process closes stdout. */
  val steps: Flow<CldStep>

  /**
   * The terminal outcome — completes once the `result` message and a clean exit are seen, or fails
   * with [CldConnectorException] on an operational death (missing result, timeout).
   */
  val result: Deferred<CldRunResult>

  /** Cancels the run's reader and kills the process tree. Idempotent. */
  override fun close()
}
