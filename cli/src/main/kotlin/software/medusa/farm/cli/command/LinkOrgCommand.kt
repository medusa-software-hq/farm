package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import software.medusa.farm.cli.api.FarmApiClient

class LinkOrgCommand : ManagementCommand(name = "link-org") {
  private val org by argument().help("The GitHub org login to link.")

  override fun help(context: Context) =
      "Link a GitHub org to the Farm app; print its installation id and reachable repositories."

  override fun run(apiClient: FarmApiClient) {
    val result = apiClient.linkOrg(org)
    echo("Installation: ${result.installationId}")
    if (result.repositories.isEmpty()) {
      echo("No repositories reachable.")
    } else {
      result.repositories.forEach { echo(it) }
    }
  }
}
