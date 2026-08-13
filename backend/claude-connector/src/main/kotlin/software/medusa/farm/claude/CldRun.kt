package software.medusa.farm.claude

import kotlinx.coroutines.flow.Flow

/**
 * A live handle to the running subprocess.
 *
 * Lifecycle: collect [messages] to consume stdout, then [awaitTermination] for the exit code and
 * captured stderr; always [close] (kills the process tree). A `use { }` block is the intended
 * pattern so cancellation or a timeout tears the whole tree down.
 */
interface CldRun : AutoCloseable {
  /**
   * Parsed NDJSON stdout messages, in order; the flow completes when the process closes stdout.
   * Cold: collecting it consumes the single underlying stream (do not collect twice).
   */
  val messages: Flow<CldMessage>

  /** Suspends until the process exits, then reports how it ended. */
  suspend fun awaitTermination(): Termination

  /** Kills the process tree. Idempotent; safe to call after normal termination. */
  override fun close()

  data class Termination(
      val exitCode: Int,
      /** Captured stderr; the driver surfaces its tail in failure messages. */
      val standardError: String,
  )
}
