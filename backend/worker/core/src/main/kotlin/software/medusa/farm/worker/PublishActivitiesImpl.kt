package software.medusa.farm.worker

import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import software.medusa.farm.claude.CldAgent
import software.medusa.farm.claude.CldCompletion
import software.medusa.farm.claude.CldRunRequest
import software.medusa.farm.claude.CldSessionSelector
import software.medusa.farm.claude.CldSessionStore
import software.medusa.farm.gitcli.GitCli
import software.medusa.farm.gitcli.GitCliAuthor
import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhRepoFullName

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
    private val sessionStore: CldSessionStore,
    private val commitAuthor: GitCliAuthor,
    private val signingKey: String?,
) : PublishActivities {
  override fun attemptIssue(
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

    val sessionId = UUID.randomUUID().toString()
    val home = sessionStore.prepare(CldSessionSelector.Fresh(sessionId))
    val workspace = Files.createTempDirectory("farm-attempt")
    val clone = workspace.resolve("repo")
    try {
      gitCli.clone(cloneUrl(repo), clone, token)
      val baseBranch = gitCli.currentBranch(clone)

      val result =
          agent
              .launch(
                  CldRunRequest(
                      workspace = clone,
                      home = home,
                      prompt = taskPrompt(title, body),
                      session = CldSessionSelector.Fresh(sessionId),
                  )
              )
              .use { it.result.await() }
      check(result.completion is CldCompletion.Ok) { "agent run ended in ${result.completion}" }

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

  private fun cloneUrl(repo: GhRepoFullName): String = "https://github.com/${repo.value}.git"

  private fun taskPrompt(title: String, body: String): String =
      "$title\n\n${body.ifBlank { "(no description)" }}"
}
