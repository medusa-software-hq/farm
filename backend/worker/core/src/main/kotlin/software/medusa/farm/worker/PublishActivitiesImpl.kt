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
import software.medusa.farm.claude.CldPermissionMode
import software.medusa.farm.claude.CldRunResult
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

      // Opened before the session starts and appended to as it goes, so what the agent is doing
      // can be read while it is still doing it rather than only once it is over. A retry opens a
      // try of its own and leaves the one it is retrying to be read back.
      val attempt = attemptNumbering.currentAttempt()
      sessionStore.startRunAttempt(sessionId, INITIAL_RUN_ORDINAL, attempt)

      val (agentEvents, result) =
          engine.runSession(
              config = sessionConfig(workspacePath = clone, configDirPath = configDir),
              prompt = taskPrompt(title, body),
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
                      ordinal = INITIAL_RUN_ORDINAL,
                      attempt = attempt,
                      position = position,
                      entry = AgentRunMapper.entry(event),
                  )
                }
                .map { it.value }
                .toList() to awaitResult()
          }
      check(result.status is CldRunStatus.Success) { "agent run ended in ${result.status}" }

      // Closed with how it went, and with a summary of it — a no-change run is still a run worth
      // showing. ordinal 0 is the initial attempt; fixups will be 1+.
      finishRun(sessionId, attempt, agentEvents, result)

      gitCli.stageAll(clone)
      if (!gitCli.hasStagedChanges(clone)) {
        return@runBlocking IssueAttemptOutcome(
            pullRequestUrl = null,
            pullRequestNumber = null,
            pullRequestHeadSha = null,
        )
      }

      val branch = "farm/issue-$number"
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

  /** Summarizes what the run did and closes it with its outcome. */
  private suspend fun finishRun(
      sessionId: String,
      attempt: Int,
      events: List<CldSessionEvent>,
      result: CldRunResult,
  ) {
    val mapped = AgentRunMapper.map(events, result)
    // The summary is a required part of the next run's context, so the summarizer is an
    // assumed-available dependency, like the agent itself: it raises when it cannot summarize, the
    // activity fails, and Temporal retries — failing the session if it stays down. That happens
    // before the PR is opened, so a retry re-runs the attempt cleanly.
    val summary = summarizer.summarize(mapped.log)
    sessionStore.finishRunAttempt(
        id = sessionId,
        ordinal = INITIAL_RUN_ORDINAL,
        attempt = attempt,
        outcome = mapped.outcome,
        cost = mapped.cost,
        summary = summary.text,
    )
  }

  private fun cloneUrl(repo: GhRepoFullName): String = "https://github.com/${repo.value}.git"

  /** Farm's standing posture for a coding session, over the workspace this attempt clones into. */
  private fun sessionConfig(workspacePath: Path, configDirPath: Path): CldSessionConfig =
      CldSessionConfig(
          workspacePath = workspacePath,
          configDirPath = configDirPath,
          permissionMode = CldPermissionMode.AcceptEdits,
          // The target repo's own settings, not the machine's.
          settingSources = listOf(CldSettingSource.Project),
          allowedToolRules = ALLOWED_TOOL_RULES,
          disallowedToolRules = DISALLOWED_TOOL_RULES,
          systemPromptSuffix = SYSTEM_PROMPT_SUFFIX,
          spendBudget = SPEND_BUDGET,
      )

  private fun taskPrompt(title: String, body: String): String =
      "$title\n\n${body.ifBlank { "(no description)" }}"

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
