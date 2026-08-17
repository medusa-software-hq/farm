package software.medusa.farm.claude

import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * The production [CldAgent]: assembles the invocation from [config] + the request, launches the
 * real `claude` binary through the commons [spawner], and drives its stream-json output through the
 * run contract — an `init`, then steps, then one terminal `result` — reporting deviations to
 * [reporter].
 *
 * `HOME` is overlaid onto [CldEngineConfig.environment] per run so the session persists under the
 * caller's directory (the hook that makes a run snapshot-able and resumable).
 *
 * The driving itself is three nested brackets, each adding one guarantee to the one inside it:
 * [withProcess] owns the process tree, [withParsedOutput] turns its stdout into the protocol's
 * phases, and [withRun] pairs those with the exit. This class only supplies the arguments and the
 * config; it is tested by pointing [executable] at a fake `claude` script.
 */
class CldProperAgent(
    private val spawner: SysProcessSpawner,
    private val executable: SysExecutableHandle,
    private val config: CldEngineConfig,
    private val reporter: CldReporter,
) : CldAgent {
  override suspend fun <T> run(request: CldRunRequest, body: suspend CldRunScope.() -> T): T =
      spawner.withProcess(
          executable = executable,
          workingDirectory = request.workspace,
          arguments = buildArguments(request),
          environment = config.environment + ("HOME" to request.home.toString()),
          reporter = reporter,
      ) {
        withParsedOutput(reporter = reporter, initTimeout = config.initTimeout) {
          withRun(
              process = this@withProcess,
              reporter = reporter,
              sessionId = request.session.sessionId,
              body = body,
          )
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
