package software.medusa.farm.worker

import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import software.medusa.farm.claude.CldAgent
import software.medusa.farm.claude.CldCompletion
import software.medusa.farm.claude.CldMessage
import software.medusa.farm.claude.CldRunRequest
import software.medusa.farm.claude.CldSessionSelector
import software.medusa.farm.claude.CldSessionStore
import software.medusa.farm.gitcli.GitCli
import software.medusa.farm.gitcli.GitCliAuthor
import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhRepoFullName
import software.medusa.farm.shared.SessionStore
import software.medusa.farm.summary.SumRunSummarizer

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
    private val agent: CldAgent,
    private val gitCli: GitCli,
    private val cldSessionStore: CldSessionStore,
    private val sessionStore: SessionStore,
    private val summarizer: SumRunSummarizer,
    private val commitAuthor: GitCliAuthor,
    private val signingKey: String?,
) : PublishActivities {
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

    val cldSessionId = UUID.randomUUID().toString()
    val home = cldSessionStore.prepare(CldSessionSelector.Fresh(cldSessionId))
    val workspace = Files.createTempDirectory("farm-attempt")
    val clone = workspace.resolve("repo")
    try {
      gitCli.clone(cloneUrl(repo), clone, token)
      val baseBranch = gitCli.currentBranch(clone)

      val messages = mutableListOf<CldMessage>()
      val result =
          agent.run(
              CldRunRequest(
                  workspace = clone,
                  home = home,
                  prompt = taskPrompt(title, body),
                  session = CldSessionSelector.Fresh(cldSessionId),
              )
          ) {
            messages += it
          }
      check(result.completion is CldCompletion.Ok) { "agent run ended in ${result.completion}" }

      // Record what the agent did (and a cheap summary of it) before publishing — a no-change run
      // is
      // still a run worth showing. ordinal 0 is the initial attempt; fixups will be 1+.
      recordRun(sessionId, messages)

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
      home.toFile().deleteRecursively()
    }
  }

  /** Maps the run's message stream to the action log, summarizes it, and stores the run. */
  private suspend fun recordRun(sessionId: String, messages: List<CldMessage>) {
    val mapped = AgentRunMapper.map(messages)
    // The summary is a required part of the next run's context, so the summarizer is an
    // assumed-available dependency, like the agent itself: it raises when it cannot summarize, the
    // activity fails, and Temporal retries — failing the session if it stays down. That happens
    // before the PR is opened, so a retry re-runs the attempt cleanly.
    val summary = summarizer.summarize(mapped.log)
    sessionStore.recordRun(
        id = sessionId,
        ordinal = INITIAL_RUN_ORDINAL,
        log = mapped.log,
        outcome = mapped.outcome,
        cost = mapped.cost,
        summary = summary.text,
    )
  }

  private fun cloneUrl(repo: GhRepoFullName): String = "https://github.com/${repo.value}.git"

  private fun taskPrompt(title: String, body: String): String =
      "$title\n\n${body.ifBlank { "(no description)" }}"

  private companion object {
    const val INITIAL_RUN_ORDINAL = 0
  }
}
