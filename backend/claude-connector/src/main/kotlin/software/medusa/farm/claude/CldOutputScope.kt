package software.medusa.farm.claude

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The middle layer: stdout read as the grammar claude actually speaks — one `init`, then a run of
 * assistant turns, then one terminal `result`. The wire union ([CldMessage]) is consumed here and
 * never handed further up; above this layer the three phases are three separate members, so no
 * consumer re-proves that the header came first.
 *
 * This layer correlates nothing with the process: it reports what the *stream* said, and whether an
 * exit agrees with it is [withRun]'s question.
 */
internal interface CldOutputScope {
  /** The opening handshake, already parsed — the block is not entered until it arrives. */
  val init: CldMessage.SystemInit

  /** The assistant turns as they stream. Backed by a channel, so collect it at most once. */
  val assistantMessages: Flow<CldMessage.Assistant>

  /** Suspends until the terminal `result` message is parsed. */
  suspend fun awaitResult(): CldMessage.Result

  /** Suspends until stdout has been read to the end — every trailing line seen and reported. */
  suspend fun awaitDrained()
}

/**
 * Waits for the handshake, then runs [body] with the rest of the stream being parsed underneath it.
 *
 * The pump runs for as long as the block does, whether or not anyone is collecting: stdout must be
 * drained even for a caller that only wants the result, or a full pipe would stall the process.
 * Anything arriving after the terminal `result` is reported and dropped — nothing should follow it.
 *
 * @param initTimeout how long to wait for the opening `init` before calling the launch failed.
 * @throws CldIllegalStartupException if the stream does not open with an `init` in time.
 * @throws CldIllegalRunException if stdout later breaks — read or parse — mid-run. It fails the
 *   pump, which cancels [body]: a broken stream ends the run rather than waiting to be asked about.
 */
internal suspend fun <T> CldProcessScope.withParsedOutput(
    reporter: CldReporter,
    initTimeout: Duration,
    body: suspend CldOutputScope.() -> T,
): T {
  // The handshake, before anything else exists: a run "starts" when claude speaks its protocol, not
  // when the OS process does, so no init in time is a failed launch — process or not.
  val opening = withTimeoutOrNull(initTimeout) { awaitOpening() }
  val handshake: CldMessage.SystemInit =
      opening?.second as? CldMessage.SystemInit
          ?: run {
            reporter.missingInit(opening?.first)
            throw CldIllegalStartupException
          }

  return coroutineScope {
    // Unbounded: the pump must keep draining the pipe even when nobody collects the turns, so this
    // buffer is what keeps a slow (or absent) consumer from stalling the process.
    val assistants = Channel<CldMessage.Assistant>(Channel.UNLIMITED)
    val result = CompletableDeferred<CldMessage.Result>()
    val drained = CompletableDeferred<Unit>()
    val pump = launch { pump(reporter, assistants, result, drained) }

    val scope =
        object : CldOutputScope {
          override val init = handshake
          override val assistantMessages = assistants.receiveAsFlow()

          override suspend fun awaitResult() = result.await()

          override suspend fun awaitDrained() = drained.await()
        }

    val outcome = scope.body()
    // The body is done with the stream; stop parsing rather than trailing the process to its exit.
    pump.cancel()
    outcome
  }
}

/**
 * The first line that is part of the protocol at all, with the message it parsed to, or null if
 * stdout ended without one. Blank and non-JSON lines are skipped rather than failing the handshake
 * — only a parseable message that is not an `init` counts as opening the stream wrongly.
 */
private suspend fun CldProcessScope.awaitOpening(): Pair<String, CldMessage>? {
  for (line in standardOutputLines) {
    val message = CldStreamParser.parseLine(line) ?: continue
    return line to message
  }
  return null
}

/** Feeds the parsed stream into the three phases until stdout ends. */
private suspend fun CldProcessScope.pump(
    reporter: CldReporter,
    assistants: SendChannel<CldMessage.Assistant>,
    result: CompletableDeferred<CldMessage.Result>,
    drained: CompletableDeferred<Unit>,
) {
  try {
    for (line in standardOutputLines) {
      val message = CldStreamParser.parseLine(line)
      when {
        message == null -> Unit // a blank or non-JSON line — not part of the protocol.
        result.isCompleted -> reporter.messageAfterResult(line)
        message is CldMessage.Assistant -> assistants.send(message)
        message is CldMessage.Result -> result.complete(message)
        else -> Unit // a duplicate init or an unknown message — nothing to act on.
      }
    }
    drained.complete(Unit)
  } catch (cancellation: CancellationException) {
    throw cancellation // the run ended around us — not a failure to surface.
  } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
    // The raw pipe/parse failure is debug data, not something the caller can act on: report it and
    // fail the run with an opaque one instead.
    reporter.streamFailed(failure)
    throw CldIllegalRunException
  } finally {
    assistants.close()
  }
}
