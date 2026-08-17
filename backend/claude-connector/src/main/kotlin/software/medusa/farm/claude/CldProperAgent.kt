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
import kotlinx.coroutines.flow.Flow
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
   * The run state machine: drains stdout — streaming steps and tracking the `init`/`result` framing
   * — then reconciles against the exit code. Runs to completion so a post-result message or a
   * missing result is caught even if the consumer stops reading early.
   */
  private suspend fun drive(
      request: CldRunRequest,
      handle: SysProcessHandle,
      steps: Channel<CldStep>,
      result: CompletableDeferred<CldRunResult>,
  ) {
    var init: CldMessage.SystemInit? = null
    var terminal: CldMessage.Result? = null
    var first = true

    handle.standardOutputLines.collect { line ->
      val message = CldStreamParser.parseLine(line) ?: return@collect
      if (first) {
        first = false
        if (message !is CldMessage.SystemInit) reporter.missingInit()
      }
      if (terminal != null) {
        reporter.messageAfterResult(line)
        return@collect
      }
      when (message) {
        is CldMessage.SystemInit -> init = message
        is CldMessage.Assistant -> steps.send(CldStep(message.text, message.toolUses))
        is CldMessage.Result -> terminal = message
        is CldMessage.Unknown -> Unit
      }
    }
    steps.close()

    val termination = handle.awaitTermination()
    val seen = terminal
    if (seen == null) {
      reporter.exitWithoutResult(termination.exitCode, termination.errorOutput)
      result.completeExceptionally(
          CldConnectorException.diedWithoutResult(termination.exitCode, termination.errorOutput)
      )
    } else {
      result.complete(
          CldRunResult(
              sessionId = init?.sessionId ?: request.session.sessionId,
              completion =
                  if (seen.isError) CldCompletion.Errored(seen.subtype) else CldCompletion.Ok,
              cost = CldRunCost(seen.totalCostUsd, seen.numTurns, seen.durationMs),
          )
      )
    }
  }

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
