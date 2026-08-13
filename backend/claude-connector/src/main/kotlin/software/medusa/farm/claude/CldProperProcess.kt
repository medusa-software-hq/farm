package software.medusa.farm.claude

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * The production [CldProcess]: drives the real `claude` binary via the commons [SysProcessSpawner].
 *
 * The subprocess mechanics — replace-not-inherit environment, streamed stdout, process-tree kill on
 * close, shutdown-hook cleanup — live in [SysProcessSpawner.launch]. This class only adapts that
 * generic handle to the claude-specific seam: it parses each stdout line into a [CldMessage] and
 * wraps a user turn in the `stream-json` envelope the CLI expects.
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
    return HandleRun(handle)
  }

  private class HandleRun(
      private val handle: SysProcessHandle,
  ) : CldRun {
    override val messages: Flow<CldMessage> =
        handle.standardOutputLines.mapNotNull { CldStreamParser.parseLine(it) }

    override suspend fun sendUserMessage(text: String) {
      handle.writeLine(renderUserMessageLine(text))
    }

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

    private companion object {
      /** Wraps raw text as a single-line `stream-json` user message. */
      fun renderUserMessageLine(text: String): String =
          buildJsonObject {
                put("type", "user")
                putJsonObject("message") {
                  put("role", "user")
                  put("content", text)
                }
              }
              .toString()
    }
  }
}
