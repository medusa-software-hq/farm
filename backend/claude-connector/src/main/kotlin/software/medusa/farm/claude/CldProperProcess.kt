package software.medusa.farm.claude

import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The production [CldProcess]: drives the real `claude` binary via [ProcessBuilder].
 *
 * The [claudeExecutable] is located once at startup and injected, so a missing binary is a clean,
 * loud failure rather than a surprise deep inside the first run.
 *
 * Two deliberate properties:
 * - **Replace-not-inherit environment.** The child gets exactly [CldInvocation.environment]; the
 *   JVM's own env is cleared first, so no host `ANTHROPIC_*`/`CLAUDE_*` or `~/.claude` leaks in.
 * - **Process-tree kill on close.** [close] destroys the process and all descendants, so
 *   cancellation or a timeout leaves nothing reparented and alive.
 */
class CldProperProcess(
    private val claudeExecutable: Path,
) : CldProcess {
  override fun spawn(invocation: CldInvocation): CldRun {
    val processBuilder =
        ProcessBuilder(listOf(claudeExecutable.toString()) + invocation.arguments)
            .directory(invocation.workingDirectory.toFile())

    // Replace-not-inherit: strip the JVM's environment, then install exactly what was requested.
    processBuilder.environment().clear()
    processBuilder.environment().putAll(invocation.environment)

    val process =
        try {
          processBuilder.start()
        } catch (e: IOException) {
          throw CldConnectorException.binaryUnavailable(cause = e)
        }

    return ProcessRun(process = process)
  }

  private class ProcessRun(
      private val process: Process,
  ) : CldRun {
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter()

    // stderr must be drained concurrently with stdout or the child can block on a full pipe; a
    // daemon thread keeps this draining off any coroutine scope.
    private val stderrBuffer = StringBuilder()
    private val stderrThread =
        Thread {
              process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                  synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                }
              }
            }
            .apply {
              isDaemon = true
              start()
            }

    override val messages: Flow<CldMessage> =
        flow {
              process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                  CldStreamParser.parseLine(line)?.let { emit(it) }
                }
              }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun sendUserMessage(text: String) {
      withContext(Dispatchers.IO) {
        stdin.write(renderUserMessageLine(text))
        stdin.newLine()
        stdin.flush()
      }
    }

    override suspend fun awaitTermination(): CldRun.Termination {
      val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
      stderrThread.join(stderrJoinMillis)
      return CldRun.Termination(
          exitCode = exitCode,
          standardError = synchronized(stderrBuffer) { stderrBuffer.toString() },
      )
    }

    override fun close() {
      // Kill descendants first, then the root, so nothing reparents and survives.
      process.descendants().forEach { it.destroyForcibly() }
      process.destroyForcibly()
      try {
        process.waitFor(closeWaitSeconds, TimeUnit.SECONDS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      try {
        stdin.close()
      } catch (_: IOException) {
        // best-effort
      }
    }

    private companion object {
      const val stderrJoinMillis = 2_000L
      const val closeWaitSeconds = 5L

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
