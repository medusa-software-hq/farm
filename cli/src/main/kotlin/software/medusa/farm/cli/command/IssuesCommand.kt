package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import software.medusa.farm.cli.api.FarmApiClient

class IssuesCommand : ManagementCommand(name = "issues") {
  override fun help(context: Context) =
      "List the open issues Farm has synced across every linked org's repos."

  override fun run(apiClient: FarmApiClient) {
    val issues = apiClient.listIssues()
    if (issues.isEmpty()) {
      echo("No issues synced.")
    } else {
      issues.forEach {
        // The wire spells a state SHOUTED_WITH_UNDERSCORES; a line someone reads wants words.
        val said = it.sessionState.lowercase().replace('_', ' ')
        val state = if (said.isEmpty()) "" else " [$said]"
        echo("${it.repoFullName}#${it.number}$state  ${it.title}")
      }
    }
  }
}
