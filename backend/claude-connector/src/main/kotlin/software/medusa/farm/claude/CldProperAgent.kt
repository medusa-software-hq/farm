package software.medusa.farm.claude

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

/**
 * The production [CldAgent]: assembles the `claude` invocation from [config] and the request,
 * drives it via [process], and closes the process tree on every exit (a timeout tears it down too).
 *
 * `HOME` is overlaid onto [CldEngineConfig.environment] per run so the session persists under the
 * caller-provided directory — the hook that makes a run snapshot-able and resumable.
 */
class CldProperAgent(
    private val process: CldProcess,
    private val config: CldEngineConfig,
) : CldAgent {
  override suspend fun run(request: CldRunRequest, onMessage: (CldMessage) -> Unit): CldRunResult {
    val invocation =
        CldInvocation(
            arguments = buildArguments(request),
            environment = config.environment + ("HOME" to request.home.toString()),
            workingDirectory = request.workspace,
        )

    val run = process.spawn(invocation)
    try {
      var systemInit: CldMessage.SystemInit? = null
      var result: CldMessage.Result? = null

      try {
        withTimeout(config.wallClockTimeout) {
          run.messages.collect { message ->
            when (message) {
              is CldMessage.SystemInit -> systemInit = message
              is CldMessage.Result -> result = message
              else -> Unit
            }
            onMessage(message)
          }
        }
      } catch (_: TimeoutCancellationException) {
        throw CldConnectorException.timedOut()
      }

      val termination = run.awaitTermination()
      val terminal =
          result
              ?: throw CldConnectorException.diedWithoutResult(
                  termination.exitCode,
                  termination.standardError,
              )

      return CldRunResult(
          sessionId = systemInit?.sessionId ?: request.session.sessionId,
          completion =
              if (terminal.isError) CldCompletion.Errored(terminal.subtype) else CldCompletion.Ok,
          cost = CldRunCost(terminal.totalCostUsd, terminal.numTurns, terminal.durationMs),
      )
    } finally {
      run.close()
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
