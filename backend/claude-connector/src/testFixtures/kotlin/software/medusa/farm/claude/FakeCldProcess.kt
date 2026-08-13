package software.medusa.farm.claude

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * A test [CldProcess] that replays caller-supplied canned message streams without spawning a real
 * binary. Each entry of [cannedRuns] is the stream for one successive [spawn], so the resume/fixup
 * path (a fresh process per bounce) can be given a distinct stream per attempt; once the runs are
 * exhausted the last one is replayed.
 *
 * Every [spawn]'s [CldInvocation] is recorded in [invocations] so tests can assert on the flags and
 * environment the agent built, and any stdin writes land in [sentMessages].
 */
class FakeCldProcess
private constructor(
    private val cannedRuns: List<List<CldMessage>>,
    private val termination: CldRun.Termination,
) : CldProcess {
  companion object {
    private val defaultTermination = CldRun.Termination(exitCode = 0, standardError = "")

    /** Single-shot: one canned stream replayed for every spawn. */
    fun of(
        cannedMessages: List<CldMessage>,
        termination: CldRun.Termination = defaultTermination,
    ): FakeCldProcess =
        FakeCldProcess(cannedRuns = listOf(cannedMessages), termination = termination)

    /** Multi-run: successive spawns replay successive streams. */
    fun withRuns(
        cannedRuns: List<List<CldMessage>>,
        termination: CldRun.Termination = defaultTermination,
    ): FakeCldProcess {
      require(cannedRuns.isNotEmpty()) { "at least one canned run is required" }
      return FakeCldProcess(cannedRuns = cannedRuns, termination = termination)
    }
  }

  val invocations: MutableList<CldInvocation> = mutableListOf()

  val lastInvocation: CldInvocation?
    get() = invocations.lastOrNull()

  val spawnCount: Int
    get() = invocations.size

  val sentMessages: MutableList<String> = mutableListOf()

  private var closedCount: Int = 0

  /** True once every spawned run has been closed (process trees killed). */
  val closed: Boolean
    get() = invocations.isNotEmpty() && closedCount == invocations.size

  override fun spawn(invocation: CldInvocation): CldRun {
    val runIndex = invocations.size
    invocations += invocation
    val cannedMessages = cannedRuns[runIndex.coerceAtMost(cannedRuns.size - 1)]

    return object : CldRun {
      override val messages: Flow<CldMessage> = cannedMessages.asFlow()

      override suspend fun sendUserMessage(text: String) {
        sentMessages += text
      }

      override suspend fun awaitTermination(): CldRun.Termination = termination

      override fun close() {
        closedCount += 1
      }
    }
  }
}
