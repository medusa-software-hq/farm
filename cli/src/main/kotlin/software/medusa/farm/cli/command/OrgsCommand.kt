package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import software.medusa.farm.cli.api.FarmApiClient

class OrgsCommand : ManagementCommand(name = "orgs") {
  override fun help(context: Context) =
      "List the GitHub orgs linked to the Farm app and their installation ids."

  override fun run(apiClient: FarmApiClient) {
    val orgs = apiClient.listLinkedOrgs()
    if (orgs.isEmpty()) {
      echo("No orgs linked.")
    } else {
      orgs.forEach { echo("${it.orgLogin} (installation ${it.installationId})") }
    }
  }
}
