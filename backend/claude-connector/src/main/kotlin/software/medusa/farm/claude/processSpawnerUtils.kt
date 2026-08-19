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

/** A process, for as long as the block it was given to lasts. */
interface SysProcessScope {
  /** Lines the process writes to its standard output; closed once it writes no more. */
  val standardOutputLineChannel: ReceiveChannel<String>

  /**
   * Waits until the process ends.
   *
   * @return How the process ended.
   */
  suspend fun awaitTermination(): SysProcessTermination
}

/** Thrown when a process could not be started at all. */
class SysProcessStartException(
    override val cause: Throwable,
) : Exception()

/**
 * Runs [block] against a freshly started process, and ends that process — together with every
 * descendant it left behind — once the block ends, however it ends.
 *
 * @return Whatever [block] returned.
 * @throws SysProcessStartException If the process could not be started.
 */
suspend fun <ResultT> SysProcessSpawner.executeProcess(
    executableHandle: SysExecutableHandle,
    workingDirectory: Path,
    arguments: List<String>,
    environment: Map<String, String> = System.getenv(),
    block: suspend SysProcessScope.() -> ResultT,
): ResultT {
  val processHandle =
      try {
        launch(
            executable = executableHandle,
            workingDirectory = workingDirectory,
            arguments = arguments,
            environment = environment,
        )
      } catch (cause: IOException) {
        throw SysProcessStartException(cause = cause)
      }

  // Nothing is ever written to the process; a child that reads its input to the end would otherwise
  // wait for input that never comes.
  processHandle.closeInput()

  // The reader and the exit watcher both sit in blocking calls that cancellation cannot interrupt —
  // only ending the process releases them. So they get a job of their own: on the way out they are
  // cancelled and never awaited, and the kill that follows is what actually lets them finish.
  // Awaiting them instead (which a plain `coroutineScope` would do) would hang here for as long as
  // the process chose to stay alive.
  val watcherScope = CoroutineScope(coroutineContext + Job())
  val scope =
      object : SysProcessScope {
        override val standardOutputLineChannel =
            processHandle.standardOutputLines.buffer(Channel.RENDEZVOUS).produceIn(watcherScope)

        private val terminationDeferred: Deferred<SysProcessTermination> = watcherScope.async {
          processHandle.awaitTermination()
        }

        override suspend fun awaitTermination(): SysProcessTermination = terminationDeferred.await()
      }

  try {
    return scope.block()
  } finally {
    watcherScope.cancel()
    processHandle.close()
  }
}
