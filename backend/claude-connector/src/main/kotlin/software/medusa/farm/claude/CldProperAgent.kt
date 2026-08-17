package software.medusa.farm.claude

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.commons.system.SysProcessTermination

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
    // A detached scope for the run's coroutines: it outlives `launch`, so it can't be a child of
    // the
    // caller's frame. close() cancels it (and kills the process); nothing else keeps it alive.
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val parsed = parse(handle, scope)
    val termination = scope.async { handle.awaitTermination() }
    // The final result surfaces reconcile's return/throw straight onto the caller's await().
    val result = scope.async { reconcile(request, parsed, termination) }
    return ProperRun(parsed.steps, result, handle, scope)
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

  private class Parsed(
      val init: Deferred<CldMessage.SystemInit>,
      val steps: Flow<CldStep>,
      val result: Deferred<CldMessage.Result>,
      /** Completes when the reader has consumed the stream to its end. */
      val drained: Job,
  )

  /**
   * Starts reading the stream. The first message must be the `init` banner; the rest stream out as
   * steps until the terminal `result`. [Parsed.init] and [Parsed.result] surface as deferreds so
   * the run can *race* the result against process termination; anomalies go to the reporter.
   */
  private fun parse(handle: SysProcessHandle, scope: CoroutineScope): Parsed {
    val init = CompletableDeferred<CldMessage.SystemInit>()
    val steps = Channel<CldStep>(Channel.UNLIMITED)
    val result = CompletableDeferred<CldMessage.Result>()
    val drained = scope.launch {
      try {
        readStream(handle, init, steps, result)
      } catch (cancellation: CancellationException) {
        throw cancellation // close() cancelled the run — not a failure to surface.
      } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        init.completeExceptionally(failure)
        result.completeExceptionally(failure)
      } finally {
        steps.close()
      }
    }
    return Parsed(init, steps.receiveAsFlow(), result, drained)
  }

  private suspend fun readStream(
      handle: SysProcessHandle,
      init: CompletableDeferred<CldMessage.SystemInit>,
      steps: SendChannel<CldStep>,
      result: CompletableDeferred<CldMessage.Result>,
  ) = coroutineScope {
    // Rendezvous: don't read the process ahead of the state machine. `steps` (UNLIMITED) is the
    // buffer that keeps a slow step-consumer from ever stalling the pipe.
    val messages =
        handle.standardOutputLines
            .mapNotNull { line -> CldStreamParser.parseLine(line)?.let { line to it } }
            .buffer(Channel.RENDEZVOUS)
            .produceIn(this)

    messages.consume {
      val opening = receiveCatching().getOrNull()?.second
      if (opening !is CldMessage.SystemInit) {
        // A stream that doesn't open with init (or is empty) is a broken contract: fail the run.
        reporter.missingInit()
        val failure = CldConnectorException.missingInit()
        init.completeExceptionally(failure)
        result.completeExceptionally(failure)
        return@consume
      }
      init.complete(opening)

      for ((line, message) in this) {
        when {
          result.isCompleted -> reporter.messageAfterResult(line)
          message is CldMessage.Assistant -> steps.send(CldStep(message.text, message.toolUses))
          message is CldMessage.Result -> result.complete(message)
          else -> Unit // a duplicate init or an unknown message — nothing to act on.
        }
      }
      // Stream ended. If no result was seen, `result` stays pending — reconcile's termination path
      // turns the exit into the verdict (a death without a result).
    }
  }

  /**
   * Reconciles the run's two ends — the parsed `result` and process termination — into a
   * [CldRunResult]. They are *raced*: the `result` message is the verdict, but the exit is what
   * says a process *died*, and either can be observed first (a result still buffered in the pipe
   * can arrive after the exit is reaped). Whichever comes first, we wait a short grace for the
   * other.
   */
  private suspend fun reconcile(
      request: CldRunRequest,
      parsed: Parsed,
      termination: Deferred<SysProcessTermination>,
  ): CldRunResult =
      try {
        withTimeout(config.wallClockTimeout) {
          // Whichever end we observe first, wait a short grace for the other: after a result, for a
          // clean exit; after an exit, for a result still buffered in the pipe.
          val (resultMessage, exit) =
              when (val first = race(parsed.result, termination)) {
                is JoinOrder.ResultFirst ->
                    first.result to withTimeoutOrNull(TERMINATION_GRACE) { termination.await() }
                is JoinOrder.TerminationFirst -> {
                  val buffered = withTimeoutOrNull(RESULT_GRACE) { parsed.result.await() }
                  if (buffered == null) {
                    reporter.exitWithoutResult(
                        first.termination.exitCode,
                        first.termination.errorOutput,
                    )
                    throw CldConnectorException.diedWithoutResult(
                        first.termination.exitCode,
                        first.termination.errorOutput,
                    )
                  }
                  buffered to first.termination
                }
              }

          if (exit == null) {
            reporter.lingeredAfterResult()
          } else {
            // The process is gone: let the reader finish (reporting any post-result lines), then
            // check the exit code against the verdict.
            withTimeoutOrNull(DRAIN_GRACE) { parsed.drained.join() }
            if (exit.exitCode != 0 && !resultMessage.isError) {
              reporter.exitDisagreedWithResult(exit.exitCode)
            }
          }
          buildResult(request, parsed, resultMessage)
        }
      } catch (_: TimeoutCancellationException) {
        throw CldConnectorException.timedOut()
      }

  private suspend fun race(
      result: Deferred<CldMessage.Result>,
      termination: Deferred<SysProcessTermination>,
  ): JoinOrder = select {
    result.onAwait { JoinOrder.ResultFirst(it) }
    termination.onAwait { JoinOrder.TerminationFirst(it) }
  }

  private suspend fun buildResult(
      request: CldRunRequest,
      parsed: Parsed,
      result: CldMessage.Result,
  ): CldRunResult =
      // A result implies the init preceded it, so `parsed.init` is already complete.
      CldRunResult(
          sessionId = parsed.init.await().sessionId ?: request.session.sessionId,
          completion =
              if (result.isError) CldCompletion.Errored(result.subtype) else CldCompletion.Ok,
          cost = CldRunCost(result.totalCostUsd, result.numTurns, result.durationMs),
      )

  private sealed interface JoinOrder {
    data class ResultFirst(val result: CldMessage.Result) : JoinOrder

    data class TerminationFirst(val termination: SysProcessTermination) : JoinOrder
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

  private companion object {
    // After its result, the process should exit almost at once; this bounds the wait before we flag
    // it as lingering.
    val TERMINATION_GRACE: Duration = 1.seconds

    // After the process exits, its final result may still sit in the pipe buffer; this bounds the
    // wait to read it before concluding the process died without one.
    val RESULT_GRACE: Duration = 100.milliseconds

    // Once the process has exited, the reader reaches EOF almost at once; this bounds the wait for
    // it
    // to finish (so post-result lines are reported) in case a leaked grandchild holds the pipe
    // open.
    val DRAIN_GRACE: Duration = 1.seconds
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
