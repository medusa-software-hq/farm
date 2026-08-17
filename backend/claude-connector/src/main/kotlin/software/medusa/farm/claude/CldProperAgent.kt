package software.medusa.farm.claude

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
  override suspend fun <T> run(request: CldRunRequest, consume: suspend (CldRun) -> T): T {
    val handle = launch(request)
    try {
      return withTimeout(config.wallClockTimeout) {
        coroutineScope {
          val result = CompletableDeferred<CldRunResult>()
          val steps = Channel<CldStep>(Channel.UNLIMITED)
          launch { drive(request, handle, steps, result) }
          consume(ProperRun(steps.receiveAsFlow(), result))
        }
      }
    } catch (_: TimeoutCancellationException) {
      throw CldConnectorException.timedOut()
    } finally {
      handle.close()
    }
  }

  private fun launch(request: CldRunRequest): SysProcessHandle {
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
  ) : CldRun

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
