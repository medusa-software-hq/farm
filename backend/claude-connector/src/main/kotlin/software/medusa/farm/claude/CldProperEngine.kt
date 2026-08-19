@file:OptIn(ExperimentalCoroutinesApi::class)

package software.medusa.farm.claude

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessScope
import software.medusa.commons.system.SysProcessSpawner

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

    val eventChannel: ReceiveChannel<CldSessionEvent>

    suspend fun awaitResultMessage(): CldProgressMessage.Result
  }

  private sealed interface ShutdownOrder {
    data class ResultFirst(
        val resultMessage: CldProgressMessage.Result,
    ) : ShutdownOrder

    data class TerminationFirst(
        val exitCode: Int,
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
            val processTerminationDeferred = async { awaitExit() }

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

                  override val eventChannel: ReceiveChannel<CldSessionEvent>
                    get() = this@parseClaudeOutput.eventChannel

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
          executable = claudeExecutableHandle,
          workingDirectory = config.workspacePath,
          arguments = arguments,
          environment = sessionEnvironment(config = config),
      ) {
        // Nothing is ever written to the session, and one that reads its input to the end would
        // otherwise wait for input that never comes — before it says even its first word.
        standardInput.close()

        block()
      }
    } catch (failure: IOException) {
      anomalyReporter.reportSpawnFailed(cause = failure)

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
    // Not a child of this scope. Reading the engine's output parks until the engine's output ends,
    // and what ends it is the engine dying — which happens outside this block, once it returns. A
    // reader here would be joined on the way out, waiting for a process that is waiting for us.
    val readerScope = CoroutineScope(currentCoroutineContext() + Job())
    val standardOutputLineChannel = standardOutput.consumeLines().produceIn(readerScope)
    val standardErrorLineChannel = standardError.consumeLines().produceIn(readerScope)

    // Unbounded, so that a block which only ever waits for the result cannot leave the engine
    // talking into a channel nobody is reading and stall it mid-session.
    val eventChannel = Channel<CldSessionEvent>(Channel.UNLIMITED)

    // Started before a first word is waited for: taking the engine's warnings makes draining them
    // ours, and one with more to warn about than it can hold would otherwise never get to speak.
    val warningPump = launch {
      for (warningLine in standardErrorLineChannel) {
        eventChannel.trySend(CldSessionEvent.Warning(text = warningLine))
      }
    }

    try {
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
                eventChannel.send(
                    CldSessionEvent.Step(assistantStep = progressMessage.assistantStep)
                )
              }

              is CldProgressMessage.Other -> Unit

              is CldProgressMessage.Result -> {
                resultMessageDeferred.complete(progressMessage)

                break
              }
            }
          }

          eventChannel.close()

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
          eventChannel.close()
        }
      }

      val scope =
          object : CldOutputScope {
            override val systemInitMessage = systemInitMessage

            override val eventChannel = eventChannel

            override suspend fun awaitResultMessage(): CldProgressMessage.Result =
                resultMessageDeferred.await()
          }

      try {
        scope.block()
      } finally {
        outputPump.cancel()
      }
    } finally {
      // Once the block is finished there is nothing left to read the engine for. The readers are
      // cancelled but never awaited; ending the engine is what actually releases them.
      warningPump.cancel()
      readerScope.cancel()
    }
  }

  private suspend fun reconcileClaudeProcess(
      observedShutdownOrder: ShutdownOrder,
      resultMessageDeferred: Deferred<CldProgressMessage.Result>,
      processTerminationDeferred: Deferred<Int>,
  ): CldRunResult =
      when (observedShutdownOrder) {
        is ShutdownOrder.ResultFirst -> {
          val exitCode =
              withTimeoutOrNull(TERMINATION_GRACE_PERIOD) { processTerminationDeferred.await() }
                  ?: run {
                    anomalyReporter.reportLingeredAfterResult()

                    throw CldAbnormalExitError
                  }

          confrontRunResult(
              runResult = observedShutdownOrder.resultMessage.runResult,
              exitCode = exitCode,
          )
        }

        is ShutdownOrder.TerminationFirst -> {
          val exitCode = observedShutdownOrder.exitCode

          val resultMessage =
              withTimeoutOrNull(RESULT_GRACE_PERIOD) { resultMessageDeferred.await() }
                  ?: run {
                    anomalyReporter.reportExitWithoutResult(
                        exitCode = exitCode,
                    )

                    throw CldAbnormalExitError
                  }

          confrontRunResult(
              runResult = resultMessage.runResult,
              exitCode = exitCode,
          )
        }
      }

  private fun confrontRunResult(
      runResult: CldRunResult,
      exitCode: Int,
  ): CldRunResult {
    when (runResult.status) {
      CldRunStatus.Success -> {
        if (exitCode != 0) {
          anomalyReporter.reportUnexpectedNonZeroExitCode(
              exitCode = exitCode,
              runResult = runResult,
          )

          throw CldAbnormalExitError
        }
      }

      is CldRunStatus.Error -> {
        if (exitCode == 0) {
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
