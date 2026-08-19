@file:OptIn(ExperimentalCoroutinesApi::class)

package software.medusa.farm.claude

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.commons.system.SysProcessTermination

/** Proper [CldEngine], backed by a real `claude` CLI. */
class CldProperEngine(
    private val processSpawner: SysProcessSpawner,
    private val claudeExecutableHandle: SysExecutableHandle,
    private val systemEnvMap: CldSystemEnvMap,
    private val authToken: CldAuthToken,
    private val anomalyReporter: CldAnomalyReporter,
) : CldEngine {
  private interface CldOutputScope {
    val systemInitMessage: CldSystemInitMessage

    val assistantStepChannel: ReceiveChannel<CldAssistantStep>

    suspend fun awaitResultMessage(): CldProgressMessage.Result
  }

  private sealed interface ShutdownOrder {
    data class ResultFirst(
        val resultMessage: CldProgressMessage.Result,
    ) : ShutdownOrder

    data class TerminationFirst(
        val termination: SysProcessTermination,
    ) : ShutdownOrder
  }

  companion object {
    // The greeting comes before any real work, so this only ever trips on an engine that started
    // without speaking.
    private val GREETING_GRACE_PERIOD: Duration = 30.seconds

    private val TERMINATION_GRACE_PERIOD: Duration = 1.seconds

    private val RESULT_GRACE_PERIOD: Duration = 100.milliseconds

    private val DRAIN_GRACE_PERIOD: Duration = 1.seconds
  }

  override suspend fun <ResultT> runSession(
      config: CldSessionConfig,
      prompt: String,
      block: suspend CldSessionScope.() -> ResultT,
  ): ResultT =
      executeClaudeProcess(
          config = config,
          prompt = prompt,
      ) {
        parseClaudeOutput {
          coroutineScope {
            val resultMessageDeferred = async { awaitResultMessage() }
            val processTerminationDeferred = async { awaitTermination() }

            val runResultDeferred = async {
              val observedShutdownOrder = select {
                resultMessageDeferred.onAwait { ShutdownOrder.ResultFirst(it) }
                processTerminationDeferred.onAwait { ShutdownOrder.TerminationFirst(it) }
              }

              reconcileClaudeProcess(
                  observedShutdownOrder = observedShutdownOrder,
                  resultMessageDeferred = resultMessageDeferred,
                  processTerminationDeferred = processTerminationDeferred,
              )
            }

            val scope =
                object : CldSessionScope {
                  override val info: CldSessionInfo
                    get() = systemInitMessage.sessionInfo

                  override val assistantStepChannel: ReceiveChannel<CldAssistantStep>
                    get() = this@parseClaudeOutput.assistantStepChannel

                  override suspend fun awaitResult(): CldRunResult = runResultDeferred.await()
                }

            try {
              scope.block()
            } finally {
              // The block is done with the session; stop watching a process it has walked away
              // from, rather than trailing it to its end.
              runResultDeferred.cancel()
              processTerminationDeferred.cancel()
              resultMessageDeferred.cancel()
            }
          }
        }
      }

  private suspend fun <ResultT> executeClaudeProcess(
      config: CldSessionConfig,
      prompt: String,
      block: suspend SysProcessScope.() -> ResultT,
  ): ResultT {
    val arguments = buildList {
      addAll(
          listOf(
              "-p",
              prompt,
              "--output-format",
              "stream-json",
              "--verbose",
              "--permission-mode",
              config.permissionMode.value,
              "--max-budget-usd",
              config.spendBudget.usdAmount.toString(),
          )
      )

      if (config.systemPromptSuffix.isNotBlank()) {
        add("--append-system-prompt")
        add(config.systemPromptSuffix)
      }

      if (config.settingSources.isNotEmpty()) {
        add("--setting-sources")
        add(config.settingSources.joinToString(separator = ",") { it.value })
      }

      if (config.allowedToolRules.isNotEmpty()) {
        add("--allowedTools")
        add(config.allowedToolRules.joinToString(separator = " ") { it.expression })
      }

      if (config.disallowedToolRules.isNotEmpty()) {
        add("--disallowedTools")
        add(config.disallowedToolRules.joinToString(separator = " ") { it.expression })
      }
    }

    return try {
      processSpawner.executeProcess(
          executableHandle = claudeExecutableHandle,
          workingDirectory = config.workspacePath,
          arguments = arguments,
          environment = sessionEnvironment(config = config),
          block = block,
      )
    } catch (failure: SysProcessStartException) {
      anomalyReporter.reportSpawnFailed(cause = failure.cause)

      throw CldAbnormalStartError
    }
  }

  /**
   * Everything the session sees of the environment, and nothing else. Built up from what the
   * session needs rather than filtered down from what the caller happens to hold.
   */
  private fun sessionEnvironment(config: CldSessionConfig): Map<String, String> =
      mapOf(
          "PATH" to systemEnvMap.path,
          "HOME" to systemEnvMap.home,
          "CLAUDE_CONFIG_DIR" to config.configDirPath.toString(),
          "CLAUDE_CODE_OAUTH_TOKEN" to authToken.token,
      )

  private suspend fun <ResultT> SysProcessScope.parseClaudeOutput(
      block: suspend CldOutputScope.() -> ResultT,
  ): ResultT = coroutineScope {
    val firstLine =
        withTimeoutOrNull(GREETING_GRACE_PERIOD) {
          standardOutputLineChannel.receiveCatching().getOrNull()
        }

    val systemInitMessage =
        firstLine?.let { CldSystemInitMessage.parse(jsonString = it) }
            ?: run {
              anomalyReporter.reportMissingInitMessage(firstLine = firstLine)

              throw CldAbnormalStartError
            }

    // Unbounded, so that a block which only ever waits for the result cannot leave the engine
    // talking into a channel nobody is reading and stall it mid-session.
    val assistantStepChannel = Channel<CldAssistantStep>(Channel.UNLIMITED)
    val resultMessageDeferred = CompletableDeferred<CldProgressMessage.Result>()

    val outputPump = launch {
      try {
        for (progressLine in standardOutputLineChannel) {
          val progressMessage =
              CldProgressMessage.parse(jsonString = progressLine)
                  ?: run {
                    anomalyReporter.reportUnexpectedProgressLine(
                        progressLine = progressLine,
                    )

                    throw CldAbnormalRunError
                  }

          when (progressMessage) {
            is CldProgressMessage.AssistantStep -> {
              assistantStepChannel.send(progressMessage.assistantStep)
            }

            is CldProgressMessage.Other -> Unit

            is CldProgressMessage.Result -> {
              resultMessageDeferred.complete(progressMessage)

              break
            }
          }
        }

        assistantStepChannel.close()

        withTimeoutOrNull(DRAIN_GRACE_PERIOD) {
          for (outputLine in standardOutputLineChannel) {
            anomalyReporter.reportOutputAfterResult(outputLine = outputLine)

            throw CldAbnormalExitError
          }
        }
            ?: run {
              anomalyReporter.reportHangOutput()

              throw CldAbnormalExitError
            }
      } finally {
        assistantStepChannel.close()
      }
    }

    val scope =
        object : CldOutputScope {
          override val systemInitMessage = systemInitMessage

          override val assistantStepChannel = assistantStepChannel

          override suspend fun awaitResultMessage(): CldProgressMessage.Result =
              resultMessageDeferred.await()
        }

    try {
      scope.block()
    } finally {
      // Same as above: once the block is finished there is nothing left to read the engine for.
      outputPump.cancel()
    }
  }

  private suspend fun reconcileClaudeProcess(
      observedShutdownOrder: ShutdownOrder,
      resultMessageDeferred: Deferred<CldProgressMessage.Result>,
      processTerminationDeferred: Deferred<SysProcessTermination>,
  ): CldRunResult =
      when (observedShutdownOrder) {
        is ShutdownOrder.ResultFirst -> {
          val processTermination =
              withTimeoutOrNull(TERMINATION_GRACE_PERIOD) { processTerminationDeferred.await() }
                  ?: run {
                    anomalyReporter.reportLingeredAfterResult()

                    throw CldAbnormalExitError
                  }

          confrontRunResult(
              runResult = observedShutdownOrder.resultMessage.runResult,
              processTermination = processTermination,
          )
        }

        is ShutdownOrder.TerminationFirst -> {
          val processTermination = observedShutdownOrder.termination

          val resultMessage =
              withTimeoutOrNull(RESULT_GRACE_PERIOD) { resultMessageDeferred.await() }
                  ?: run {
                    anomalyReporter.reportExitWithoutResult(
                        exitCode = processTermination.exitCode,
                    )

                    throw CldAbnormalExitError
                  }

          confrontRunResult(
              runResult = resultMessage.runResult,
              processTermination = processTermination,
          )
        }
      }

  private fun confrontRunResult(
      runResult: CldRunResult,
      processTermination: SysProcessTermination,
  ): CldRunResult {
    when (runResult.status) {
      CldRunStatus.Success -> {
        if (processTermination.exitCode != 0) {
          anomalyReporter.reportUnexpectedNonZeroExitCode(
              exitCode = processTermination.exitCode,
              runResult = runResult,
          )

          throw CldAbnormalExitError
        }
      }

      is CldRunStatus.Error -> {
        if (processTermination.exitCode == 0) {
          anomalyReporter.reportUnexpectedZeroExitCode(
              runResult = runResult,
          )

          throw CldAbnormalExitError
        }
      }
    }

    return runResult
  }
}
