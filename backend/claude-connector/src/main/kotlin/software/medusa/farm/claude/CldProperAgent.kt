package software.medusa.farm.claude

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * The production [CldAgent]: assembles the invocation from [config] + the request, launches the
 * real `claude` binary through the commons [spawner], and drives its stream-json output through the
 * run contract — an `init`, then steps, then one terminal `result` — reporting deviations to
 * [reporter].
 *
 * `HOME` is overlaid onto [CldEngineConfig.environment] per run so the session persists under the
 * caller's directory (the hook that makes a run snapshot-able and resumable). There is deliberately
 * no `Cld` process wrapper: the subprocess seam is commons' [SysProcessHandle], and the driver is
 * tested by pointing [executable] at a fake `claude` script.
 */
class CldProperAgent(
    private val spawner: SysProcessSpawner,
    private val executable: SysExecutableHandle,
    private val config: CldEngineConfig,
    private val reporter: CldReporter,
) : CldAgent {
  override fun launch(request: CldRunRequest): CldRun {
    val handle = spawn(request)
    val steps = Channel<CldStep>(Channel.UNLIMITED)
    val result = CompletableDeferred<CldRunResult>()
    // A detached scope for the reader: the run outlives `launch`, so it can't be a child of the
    // caller's frame. close() cancels it (and kills the process); nothing else keeps it alive.
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    scope.launch {
      try {
        withTimeout(config.wallClockTimeout) { drive(request, handle, steps, result) }
      } catch (_: TimeoutCancellationException) {
        steps.close()
        result.completeExceptionally(CldConnectorException.timedOut())
      } catch (cancellation: CancellationException) {
        throw cancellation // close() cancelled the run — not a failure to surface.
      } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        // Marshal any reader failure to the awaiter rather than letting it vanish into the scope.
        steps.close()
        result.completeExceptionally(failure)
      }
    }
    return ProperRun(steps.receiveAsFlow(), result, handle, scope)
  }

  private fun spawn(request: CldRunRequest): SysProcessHandle {
    val handle =
        try {
          spawner.launch(
              executable = executable,
              workingDirectory = request.workspace,
              arguments = buildArguments(request),
              environment = config.environment + ("HOME" to request.home.toString()),
          )
        } catch (e: IOException) {
          throw CldConnectorException.binaryUnavailable(cause = e)
        }
    // The connector drives claude through `-p` and never writes stdin; headless claude blocks
    // reading stdin until EOF, so close it now or the whole run hangs.
    handle.closeInput()
    return handle
  }

  /**
   * Reconciles the two ends of a run — the stdout stream and the process exit — into [result].
   *
   * The `result` message is the authoritative terminus: when [readStream] reaches it, that *is* the
   * verdict and there is nothing to wait for (the process is on its way out; [CldRun.close] makes
   * sure of it). Only when the stream ends *without* a result do we ask the exit code why the
   * process died. So the two are not raced — the stream leads, and termination is the fallback the
   * no-result case falls back to; the wall-clock guard above covers a process that closes stdout
   * but lingers.
   */
  private suspend fun drive(
      request: CldRunRequest,
      handle: SysProcessHandle,
      steps: SendChannel<CldStep>,
      result: CompletableDeferred<CldRunResult>,
  ) = coroutineScope {
    // Rendezvous: don't read the process ahead of the state machine. `steps` (UNLIMITED) is the
    // buffer that keeps a slow step-consumer from ever stalling the pipe.
    val messages =
        handle.standardOutputLines
            .mapNotNull { line -> CldStreamParser.parseLine(line)?.let { line to it } }
            .buffer(Channel.RENDEZVOUS)
            .produceIn(this)

    val outcome = readStream(messages, steps)
    steps.close()

    val terminal = outcome.terminal
    if (terminal != null) {
      result.complete(
          CldRunResult(
              sessionId = outcome.sessionId ?: request.session.sessionId,
              completion =
                  if (terminal.isError) CldCompletion.Errored(terminal.subtype)
                  else CldCompletion.Ok,
              cost = CldRunCost(terminal.totalCostUsd, terminal.numTurns, terminal.durationMs),
          )
      )
    } else {
      val termination = handle.awaitTermination()
      reporter.exitWithoutResult(termination.exitCode, termination.errorOutput)
      result.completeExceptionally(
          CldConnectorException.diedWithoutResult(termination.exitCode, termination.errorOutput)
      )
    }
  }

  /**
   * Consumes the parsed stream to its end. The first message must be the `init` banner — a stream
   * that opens otherwise is a broken contract, reported and failed. The rest stream out as steps
   * until the terminal `result`; anything after it is reported, not acted on.
   */
  private suspend fun readStream(
      messages: ReceiveChannel<Pair<String, CldMessage>>,
      steps: SendChannel<CldStep>,
  ): StreamOutcome {
    val init = messages.receiveCatching().getOrNull()?.second ?: return StreamOutcome(null, null)
    if (init !is CldMessage.SystemInit) {
      reporter.missingInit()
      throw CldConnectorException.missingInit()
    }

    var terminal: CldMessage.Result? = null
    for ((line, message) in messages) {
      when {
        terminal != null -> reporter.messageAfterResult(line)
        message is CldMessage.Assistant -> steps.send(CldStep(message.text, message.toolUses))
        message is CldMessage.Result -> terminal = message
        else -> Unit
      }
    }
    return StreamOutcome(init.sessionId, terminal)
  }

  private class StreamOutcome(val sessionId: String?, val terminal: CldMessage.Result?)

  private class ProperRun(
      override val steps: Flow<CldStep>,
      override val result: Deferred<CldRunResult>,
      private val handle: SysProcessHandle,
      private val scope: CoroutineScope,
  ) : CldRun {
    override fun close() {
      scope.cancel()
      handle.close()
    }
  }

  private fun buildArguments(request: CldRunRequest): List<String> {
    val args =
        mutableListOf(
            "-p",
            request.prompt,
            "--output-format",
            "stream-json",
            "--verbose",
            "--setting-sources",
            config.toolPolicy.settingSources,
            "--permission-mode",
            config.toolPolicy.permissionMode,
        )

    if (config.toolPolicy.allowedTools.isNotEmpty()) {
      args += "--allowedTools"
      args += config.toolPolicy.allowedTools.joinToString(" ")
    }
    if (config.toolPolicy.disallowedTools.isNotEmpty()) {
      args += "--disallowedTools"
      args += config.toolPolicy.disallowedTools.joinToString(" ")
    }

    when (val session = request.session) {
      // A fresh run fixes its own id so we can snapshot and resume it; a resume replays the prior.
      is CldSessionSelector.Fresh -> {
        args += "--session-id"
        args += session.sessionId
      }
      is CldSessionSelector.Resume -> {
        args += "--resume"
        args += session.from.sessionId
      }
    }

    config.model?.let {
      args += "--model"
      args += it
    }
    config.maxBudgetUsd?.let {
      args += "--max-budget-usd"
      args += it.toString()
    }
    if (config.appendSystemPrompt.isNotBlank()) {
      args += "--append-system-prompt"
      args += config.appendSystemPrompt
    }

    return args
  }
}
