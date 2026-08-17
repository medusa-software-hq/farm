package software.medusa.farm.claude

import java.io.IOException
import java.nio.file.Path
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.commons.system.SysProcessTermination

/**
 * The subprocess, for the length of a block — the innermost of the connector's three layers. It
 * knows nothing of claude's protocol: it hands out raw stdout lines and the exit, and guarantees
 * the process tree is dead once the block ends, however it ends.
 *
 * Both members are already *running*, which is the point of the layer: the reads that cannot be
 * cancelled are confined here, so every layer above waits only on a channel or a [Deferred] and can
 * use plain `coroutineScope` without risking a hang. See [withProcess] for why that matters.
 */
internal interface CldProcessScope {
  /**
   * The process's stdout, one line at a time, in order; closed when stdout does. Rendezvous-
   * buffered, so the process is never read ahead of whoever is consuming it.
   */
  val standardOutputLines: ReceiveChannel<String>

  /** The exit code and captured stderr, once the process ends. */
  val termination: Deferred<SysProcessTermination>
}

/**
 * Runs [body] against a freshly spawned process, then kills its tree. The block owns the process
 * for its whole extent: an early return, a throw, or cancellation of the caller all reach the kill
 * in the `finally`, so no path leaves a `claude` behind.
 *
 * The reader and the exit watcher are deliberately *not* children of the caller's scope. Both sit
 * in blocking JVM calls (`readLine` on the stdout pipe, `waitFor` on the process) that coroutine
 * cancellation cannot interrupt — only the kill below unblocks them. A plain `coroutineScope` would
 * join them on the way out and hang for as long as a lingering claude stayed alive, so they get
 * their own [Job]: cancelled, not joined, and then killed.
 *
 * @param reporter told about a spawn failure before it is turned into a typed launch failure.
 * @throws CldCorruptedInstallationException if the executable could not be started at all.
 */
internal suspend fun <T> SysProcessSpawner.withProcess(
    executable: SysExecutableHandle,
    workingDirectory: Path,
    arguments: List<String>,
    environment: Map<String, String>,
    reporter: CldReporter,
    body: suspend CldProcessScope.() -> T,
): T {
  val handle =
      try {
        launch(
            executable = executable,
            workingDirectory = workingDirectory,
            arguments = arguments,
            environment = environment,
        )
      } catch (e: IOException) {
        reporter.spawnFailed(e)
        throw CldCorruptedInstallationException
      }
  // The connector drives claude through `-p` and never writes stdin; headless claude blocks reading
  // stdin until EOF, so close it now or the whole run hangs.
  handle.closeInput()

  val watchers = CoroutineScope(coroutineContext + Job())
  val scope =
      object : CldProcessScope {
        override val standardOutputLines =
            handle.standardOutputLines.buffer(Channel.RENDEZVOUS).produceIn(watchers)
        override val termination = watchers.async { handle.awaitTermination() }
      }

  try {
    return scope.body()
  } finally {
    watchers.cancel()
    handle.close()
  }
}
