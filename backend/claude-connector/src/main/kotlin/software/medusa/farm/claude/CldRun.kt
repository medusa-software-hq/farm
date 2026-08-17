package software.medusa.farm.claude

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/**
 * A live run, handed to the block passed to [CldAgent.run]. Collect [steps] to observe the agent's
 * actions as they stream; await [result] for the terminal outcome. Both are valid only within that
 * block — the process is torn down when the block returns.
 */
interface CldRun {
  /** The agent's steps as they stream; completes when the process closes stdout. */
  val steps: Flow<CldStep>

  /**
   * The terminal outcome — completes once the `result` message and a clean exit are seen, or fails
   * with [CldConnectorException] if the process died without a result.
   */
  val result: Deferred<CldRunResult>
}
