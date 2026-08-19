package software.medusa.farm.claude

import kotlinx.coroutines.channels.ReceiveChannel

/** Claude session scope. */
interface CldSessionScope {
  /** Basic info about this session. */
  val info: CldSessionInfo

  /** What this session produces as it runs, in the order it reached us. */
  val eventChannel: ReceiveChannel<CldSessionEvent>

  /**
   * Waits until the session ends.
   *
   * A session the assistant itself ends badly is returned like any other; only an engine that
   * misbehaves raises a [CldError], and it does so out of [CldEngine.runSession] rather than here.
   *
   * @return Run result of this session.
   */
  suspend fun awaitResult(): CldRunResult
}
