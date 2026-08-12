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
      issues.forEach { echo("${it.repoFullName}#${it.number}  ${it.title}") }
    }
  }
}
