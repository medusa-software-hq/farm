package software.medusa.farm.claude

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * The production [CldProcess]: drives the real `claude` binary via the commons [SysProcessSpawner].
 *
 * The subprocess mechanics — replace-not-inherit environment, streamed stdout, process-tree kill on
 * close, shutdown-hook cleanup — live in [SysProcessSpawner.launch]. This class only adapts that
 * generic handle to the claude-specific seam: parsing each stdout line into a [CldMessage].
 *
 * The [executable] is a validated handle resolved once at startup, so a missing binary is a clean,
 * loud failure rather than a surprise deep inside the first run.
 */
class CldProperProcess(
    private val spawner: SysProcessSpawner,
    private val executable: SysExecutableHandle,
) : CldProcess {
  override fun spawn(invocation: CldInvocation): CldRun {
    val handle =
        try {
          spawner.launch(
              executable = executable,
              workingDirectory = invocation.workingDirectory,
              arguments = invocation.arguments,
              environment = invocation.environment,
          )
        } catch (e: IOException) {
          throw CldConnectorException.binaryUnavailable(cause = e)
        }
    // The connector drives claude entirely through `-p` and never writes stdin, so close it now:
    // headless claude blocks reading stdin until EOF, so an open pipe would hang the whole run.
    handle.closeInput()
    return HandleRun(handle)
  }

  private class HandleRun(
      private val handle: SysProcessHandle,
  ) : CldRun {
    override val messages: Flow<CldMessage> =
        handle.standardOutputLines.mapNotNull { CldStreamParser.parseLine(it) }

    override suspend fun awaitTermination(): CldRun.Termination {
      val termination = handle.awaitTermination()
      return CldRun.Termination(
          exitCode = termination.exitCode,
          standardError = termination.errorOutput,
      )
    }

    override fun close() {
      handle.close()
    }
  }
}
