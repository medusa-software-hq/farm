package software.medusa.farm.claude

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.commons.system.SysProcessTermination

/**
 * One live `claude` run, for as long as the [CldAgent.run] block lasts: [info] is what the opening
 * handshake reported, [steps] streams the agent's actions, and [await] gives the terminal outcome.
 *
 * The scope is only valid inside the block — do not store it or let it outlive the call. That
 * restriction is what replaces a `close()` the caller could forget: the process is killed when the
 * block ends, so a run cannot be leaked.
 *
 * Nothing here reports an operational failure. A run that dies fails the whole [CldAgent.run] call
 * instead, cancelling the block wherever it happens to be — so failure is handled once, around the
 * call, and never checked for inside it.
 */
interface CldRunScope {
  /** What the opening `init` handshake reported — available now, not at the end of the run. */
  val info: CldRunInfo

  /**
   * The agent's steps as they stream; completes when the process closes stdout. Backed by a
   * channel, so collect it at most once.
   */
  val steps: Flow<CldStep>

  /**
   * Suspends until the run's terminal outcome is known. Returns for *every* outcome the agent
   * itself produced, including a failed one ([CldCompletion.Errored]); an operational death is not
   * returned here but cancels the block, so this never throws a [CldRunException] of its own.
   */
  suspend fun await(): CldRunResult
}

/**
 * The outermost layer: pairs the stream's verdict with the process's exit and runs [body] against
 * the result.
 *
 * A watchdog reconciles the two ends for as long as the block lasts. It is what makes an
 * operational death reach the caller even when [body] never asks — it fails the enclosing scope,
 * which cancels [body] and throws out of the whole call.
 *
 * @param sessionId the id to fall back on when the handshake did not name one.
 * @throws CldIllegalExitException if the process exits without ever emitting its terminal `result`.
 */
internal suspend fun <T> CldOutputScope.withRun(
    process: CldProcessScope,
    reporter: CldReporter,
    sessionId: String,
    body: suspend CldRunScope.() -> T,
): T = coroutineScope {
  val outcome = CompletableDeferred<CldRunResult>()
  val watchdog = launch { outcome.complete(reconcile(process.termination, reporter)) }

  val scope =
      object : CldRunScope {
        override val info = CldRunInfo(init.sessionId ?: sessionId, init.model, init.tools)
        override val steps = assistantMessages.map { CldStep(it.text, it.toolUses) }

        override suspend fun await() = outcome.await()
      }

  val result = scope.body()
  // The body is finished with the run; stop reconciling rather than trailing a process it walked
  // away from.
  watchdog.cancel()
  result
}

/**
 * Reconciles the run's two ends — the parsed `result` and process termination — into a
 * [CldRunResult]. They are *raced*: the `result` message is the verdict, but the exit is what says
 * a process *died*, and either can be observed first (a result still buffered in the pipe can
 * arrive after the exit is reaped). Whichever comes first, we wait a short grace for the other.
 *
 * There is no wall-clock bound here: the process terminating is the guaranteed backstop that
 * resolves the race, and how long to tolerate a *running* agent is a use-site concern the caller
 * bounds around [CldAgent.run], not the library's.
 */
private suspend fun CldOutputScope.reconcile(
    termination: Deferred<SysProcessTermination>,
    reporter: CldReporter,
): CldRunResult = coroutineScope {
  val result = async { awaitResult() }
  try {
    val (message, exit) =
        when (val first = race(result, termination)) {
          is JoinOrder.ResultFirst ->
              first.result to withTimeoutOrNull(TERMINATION_GRACE) { termination.await() }
          is JoinOrder.TerminationFirst -> {
            val buffered = withTimeoutOrNull(RESULT_GRACE) { result.await() }
            if (buffered == null) {
              reporter.exitWithoutResult(first.termination.exitCode, first.termination.errorOutput)
              throw CldIllegalExitException
            }
            buffered to first.termination
          }
        }

    if (exit == null) {
      reporter.lingeredAfterResult()
    } else {
      // The process is gone: let the pump finish (reporting any post-result lines), then check the
      // exit code against the verdict.
      withTimeoutOrNull(DRAIN_GRACE) { awaitDrained() }
      if (exit.exitCode != 0 && !message.isError) {
        reporter.exitDisagreedWithResult(exit.exitCode)
      }
    }
    buildResult(message)
  } finally {
    result.cancel()
  }
}

private suspend fun race(
    result: Deferred<CldMessage.Result>,
    termination: Deferred<SysProcessTermination>,
): JoinOrder = select {
  result.onAwait { JoinOrder.ResultFirst(it) }
  termination.onAwait { JoinOrder.TerminationFirst(it) }
}

private fun buildResult(result: CldMessage.Result): CldRunResult =
    CldRunResult(
        completion =
            if (result.isError) CldCompletion.Errored(result.subtype) else CldCompletion.Ok,
        cost = CldRunCost(result.totalCostUsd, result.numTurns, result.durationMs),
    )

private sealed interface JoinOrder {
  data class ResultFirst(val result: CldMessage.Result) : JoinOrder

  data class TerminationFirst(val termination: SysProcessTermination) : JoinOrder
}

// After its result, the process should exit almost at once; this bounds the wait before we flag it
// as lingering.
private val TERMINATION_GRACE: Duration = 1.seconds

// After the process exits, its final result may still sit in the pipe buffer; this bounds the wait
// to read it before concluding the process died without one.
private val RESULT_GRACE: Duration = 100.milliseconds

// Once the process has exited, the pump reaches EOF almost at once; this bounds the wait for it to
// finish (so post-result lines are reported) in case a leaked grandchild holds the pipe open.
private val DRAIN_GRACE: Duration = 1.seconds
