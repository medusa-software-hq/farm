package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import software.medusa.farm.cli.api.FarmApiClient

class SyncCommand : ManagementCommand(name = "sync") {
  override fun help(context: Context) =
      "Trigger a repo sync for every linked org now, instead of waiting for the periodic sweep."

  override fun run(apiClient: FarmApiClient) {
    apiClient.syncRepositories()
    echo("Sync started.")
  }
}
