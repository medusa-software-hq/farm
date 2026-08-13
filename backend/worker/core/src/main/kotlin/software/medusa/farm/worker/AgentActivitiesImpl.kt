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
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhRepoFullName

/**
 * Fetches the issue body, drives the [agent] to summarize it, and returns the assistant's text. The
 * throwaway home and workspace are removed after each run so a long-lived worker doesn't accumulate
 * per-issue state on disk.
 */
class AgentActivitiesImpl(
    private val clientProvider: GhInstallationApiClientProvider,
    private val agent: CldAgent,
    private val sessionStore: CldSessionStore,
) : AgentActivities {
  override fun summarizeIssue(
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
  ): String = runBlocking {
    val body =
        clientProvider
            .provideForInstallation(GhInstallationId(installationId))
            .getIssueBody(GhRepoFullName(repoFullName), number)

    val sessionId = UUID.randomUUID().toString()
    val home = sessionStore.prepare(CldSessionSelector.Fresh(sessionId))
    val workspace = Files.createTempDirectory("farm-summarize")
    try {
      val summary = StringBuilder()
      val result =
          agent.run(
              CldRunRequest(
                  workspace = workspace,
                  home = home,
                  prompt = summaryPrompt(title, body),
                  session = CldSessionSelector.Fresh(sessionId),
              )
          ) { message ->
            if (message is CldMessage.Assistant && message.text.isNotBlank()) {
              summary.appendLine(message.text)
            }
          }
      check(result.completion is CldCompletion.Ok) {
        "issue summary run ended in ${result.completion}"
      }
      summary.toString().trim().ifEmpty { "The agent produced no summary." }
    } finally {
      workspace.toFile().deleteRecursively()
      home.toFile().deleteRecursively()
    }
  }

  private fun summaryPrompt(title: String, body: String): String =
      "Summarize the following GitHub issue in two or three plain sentences, for a maintainer " +
          "skimming their queue. Output only the summary — no preamble, no tools.\n\n" +
          "Title: $title\n\n${body.ifBlank { "(no description)" }}"
}
