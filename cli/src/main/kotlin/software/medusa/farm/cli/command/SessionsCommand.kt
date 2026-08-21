package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import software.medusa.farm.cli.api.FarmApiClient

class SessionsCommand : ManagementCommand(name = "sessions") {
  override fun help(context: Context) =
      "List the sessions Farm has opened, newest first, and what each is doing."

  override fun run(apiClient: FarmApiClient) {
    val sessions = apiClient.listSessions()
    if (sessions.isEmpty()) {
      echo("No sessions.")
      return
    }

    sessions.forEach {
      echo("${it.id}  ${it.repoFullName}#${it.number}  ${SessionState.describe(it.state)}")
      echo("    ${it.title}")
      if (it.pullRequestUrl.isNotEmpty()) echo("    ${it.pullRequestUrl}")
    }
  }
}
