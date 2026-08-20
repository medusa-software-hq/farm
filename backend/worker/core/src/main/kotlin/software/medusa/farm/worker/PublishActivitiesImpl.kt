package software.medusa.farm.worker

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import software.medusa.farm.claude.CldCost
import software.medusa.farm.claude.CldEngine
import software.medusa.farm.claude.CldModelId
import software.medusa.farm.claude.CldPermissionMode
import software.medusa.farm.claude.CldRunStatus
import software.medusa.farm.claude.CldSessionConfig
import software.medusa.farm.claude.CldSessionEvent
import software.medusa.farm.claude.CldSettingSource
import software.medusa.farm.claude.CldToolRule
import software.medusa.farm.gitcli.GitCli
import software.medusa.farm.gitcli.GitCliAuthor
import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.shared.AttemptNumbering
import software.medusa.farm.shared.SessionRunAttempt
import software.medusa.farm.shared.SessionStore

/**
 * Clones the issue's repo, runs the agent against it, and publishes the result: if the working tree
 * changed, commits it to a `farm/issue-<n>` branch, pushes, and opens a pull request. The throwaway
 * clone, home, and workspace are removed after each attempt.
 *
 * git needs a raw installation token (minted here via [tokenMinter]) for clone/push, distinct from
 * the API clients [clientProvider] hands out for the issue read and PR open.
 */
class PublishActivitiesImpl(
    private val clientProvider: GhInstallationApiClientProvider,
    private val tokenMinter: GhAppApiClient,
    private val engine: CldEngine,
    private val gitCli: GitCli,
    private val sessionStore: SessionStore,
    private val summarizer: RunSummarizer,
    private val commitAuthor: GitCliAuthor,
    private val signingKey: String?,
    private val attemptNumbering: AttemptNumbering,
    private val claudeModel: CldModelId,
) : PublishActivities {
  private val logger = LoggerFactory.getLogger(PublishActivitiesImpl::class.java)

  override fun attemptIssue(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
  ): IssueAttemptOutcome = runBlocking {
    val installation = GhInstallationId(installationId)
    val repo = GhRepoFullName(repoFullName)
    val client = clientProvider.provideForInstallation(installation)
    val body = client.getIssueBody(repo, number)
    val token = tokenMinter.mintInstallationToken(installation).token

    val workspace = Files.createTempDirectory("farm-attempt")
    val clone = workspace.resolve("repo")
    // The assistant's own state, kept per attempt so one attempt cannot read another's.
    val configDir = Files.createTempDirectory("farm-attempt-claude")
    try {
      gitCli.clone(cloneUrl(repo), clone, token)
      val baseBranch = gitCli.currentBranch(clone)

      runAgent(
          sessionId = sessionId,
          runOrdinal = INITIAL_RUN_ORDINAL,
          workspacePath = clone,
          configDirPath = configDir,
          prompt = AgentPrompt.forIssue(title = title, body = body),
      )

      gitCli.stageAll(clone)
      if (!gitCli.hasStagedChanges(clone)) {
        return@runBlocking IssueAttemptOutcome(
            pullRequestUrl = null,
            pullRequestNumber = null,
            pullRequestHeadSha = null,
        )
      }

      val branch = branchFor(number)
      gitCli.createBranch(clone, branch)
      gitCli.commit(clone, "$title\n\nRefs #$number", commitAuthor, signingKey)
      gitCli.push(clone, branch, token)
      val pullRequest =
          client.createPullRequest(
              repo,
              head = branch,
              base = baseBranch,
              title = title,
              body = "Refs #$number\n\n🌱 Opened by Farm.",
          )
      IssueAttemptOutcome(
          pullRequestUrl = pullRequest.url,
          pullRequestNumber = pullRequest.number,
          pullRequestHeadSha = pullRequest.headSha,
      )
    } finally {
      workspace.toFile().deleteRecursively()
      configDir.toFile().deleteRecursively()
    }
  }

  override fun fixupIssue(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
      runOrdinal: Int,
      feedback: ReviewFeedback,
  ): Unit = runBlocking {
    val installation = GhInstallationId(installationId)
    val repo = GhRepoFullName(repoFullName)
    val client = clientProvider.provideForInstallation(installation)
    val body = client.getIssueBody(repo, number)
    val token = tokenMinter.mintInstallationToken(installation).token
    val branch = branchFor(number)

    val workspace = Files.createTempDirectory("farm-fixup")
    val clone = workspace.resolve("repo")
    val configDir = Files.createTempDirectory("farm-fixup-claude")
    try {
      gitCli.clone(cloneUrl(repo), clone, token)
      // Onto the branch the pull request is on, so the agent sees the work being reviewed rather
      // than the trunk it was branched from.
      gitCli.checkout(clone, branch)

      runAgent(
          sessionId = sessionId,
          runOrdinal = runOrdinal,
          workspacePath = clone,
          configDirPath = configDir,
          prompt =
              AgentPrompt.forFixup(
                  title = title,
                  body = body,
                  previousSummary = previousRunSummary(sessionId),
                  feedback = feedback,
              ),
      )

      gitCli.stageAll(clone)
      // A review can be answered without changing anything — the reviewer was mistaken, or asked
      // for something already there. The run is still recorded; there is simply nothing to push.
      if (gitCli.hasStagedChanges(clone)) {
        gitCli.commit(clone, fixupCommitMessage(number), commitAuthor, signingKey)
        gitCli.push(clone, branch, token)
      }
    } finally {
      workspace.toFile().deleteRecursively()
      configDir.toFile().deleteRecursively()
    }
  }

  /**
   * Runs one agent session over [workspacePath] as run [runOrdinal], recording what it does as it
   * does it and closing the run with a summary of it.
   */
  private suspend fun runAgent(
      sessionId: String,
      runOrdinal: Int,
      workspacePath: Path,
      configDirPath: Path,
      prompt: String,
  ) {
    // Opened before the session starts and appended to as it goes, so what the agent is doing can
    // be read while it is still doing it rather than only once it is over. A retry opens a try of
    // its own and leaves the one it is retrying to be read back.
    val attempt = attemptNumbering.currentAttempt()
    sessionStore.startRunAttempt(sessionId, runOrdinal, attempt)

    val (agentEvents, result) =
        engine.runSession(
            config = sessionConfig(workspacePath = workspacePath, configDirPath = configDirPath),
            prompt = prompt,
        ) {
          eventChannel
              .receiveAsFlow()
              .onEach { event ->
                if (event is CldSessionEvent.Warning) {
                  logger.warn("The agent session warned: {}", event.text)
                }
              }
              .withIndex()
              .onEach { (position, event) ->
                sessionStore.appendRunEntry(
                    id = sessionId,
                    ordinal = runOrdinal,
                    attempt = attempt,
                    position = position,
                    entry = AgentRunMapper.entry(event),
                )
              }
              .map { it.value }
              .toList() to awaitResult()
        }
    check(result.status is CldRunStatus.Success) { "agent run ended in ${result.status}" }

    val mapped = AgentRunMapper.map(agentEvents, result)
    // The summary is a required part of the next run's context, so the summarizer is an
    // assumed-available dependency, like the agent itself: it raises when it cannot summarize, the
    // activity fails, and Temporal retries — failing the session if it stays down. That happens
    // before anything is published, so a retry re-runs the attempt cleanly.
    val summary = summarizer.summarize(mapped.log)
    sessionStore.finishRunAttempt(
        id = sessionId,
        ordinal = runOrdinal,
        attempt = attempt,
        outcome = mapped.outcome,
        cost = mapped.cost,
        summary = summary.text,
    )
  }

  /** What the run before this one did, as it was summarized when it closed. */
  private suspend fun previousRunSummary(sessionId: String): String =
      sessionStore
          .getRuns(sessionId)
          .flatMap { it.attempts }
          .filterIsInstance<SessionRunAttempt.Finished>()
          .lastOrNull()
          ?.summary ?: error("session $sessionId has no finished run to follow up")

  private fun branchFor(number: Int): String = "farm/issue-$number"

  private fun fixupCommitMessage(number: Int): String = "Address review feedback\n\nRefs #$number"

  private fun cloneUrl(repo: GhRepoFullName): String = "https://github.com/${repo.value}.git"

  /** Farm's standing posture for a coding session, over the workspace this attempt clones into. */
  private fun sessionConfig(workspacePath: Path, configDirPath: Path): CldSessionConfig =
      CldSessionConfig(
          workspacePath = workspacePath,
          configDirPath = configDirPath,
          model = claudeModel,
          permissionMode = CldPermissionMode.AcceptEdits,
          // The target repo's own settings, not the machine's.
          settingSources = listOf(CldSettingSource.Project),
          allowedToolRules = ALLOWED_TOOL_RULES,
          disallowedToolRules = DISALLOWED_TOOL_RULES,
          systemPromptSuffix = SYSTEM_PROMPT_SUFFIX,
          spendBudget = SPEND_BUDGET,
      )

  private companion object {
    const val INITIAL_RUN_ORDINAL = 0

    val ALLOWED_TOOL_RULES =
        listOf(
            CldToolRule.Read,
            CldToolRule.Edit,
            CldToolRule.Write,
            CldToolRule.Glob,
            CldToolRule.Grep,
            CldToolRule.Task,
            CldToolRule.Bash(commandMask = null),
        )

    // Publishing belongs to the caller, not the assistant, and there is no user to answer a
    // question.
    val DISALLOWED_TOOL_RULES =
        listOf(
            CldToolRule.Bash(CldToolRule.Bash.CommandMask("git push:*")),
            CldToolRule.Bash(CldToolRule.Bash.CommandMask("gh:*")),
            CldToolRule.WebFetch,
            CldToolRule.WebSearch,
            CldToolRule.AskUserQuestion,
        )

    // A runaway guard, not a target: a real multi-file task legitimately spends a few dollars of
    // tool calls.
    val SPEND_BUDGET = CldCost(usdAmount = 10.00)

    val SYSTEM_PROMPT_SUFFIX =
        "You are an autonomous coding agent running non-interactively. The single message you are " +
            "given is the text of a GitHub issue, and your job is to implement and solve it " +
            "fully. Do not wait for further instructions or scope confirmation, and never ask " +
            "for clarification; make reasonable assumptions and implement. Do not push commits " +
            "or open pull requests yourself."
  }
}
