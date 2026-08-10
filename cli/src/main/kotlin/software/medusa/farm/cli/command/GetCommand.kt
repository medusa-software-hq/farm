package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import software.medusa.farm.cli.api.FarmApiClient

class GetCommand : ManagementCommand(name = "get") {
  override fun help(context: Context) = "Print the current counter value."

  override fun run(apiClient: FarmApiClient) {
    echo("Count: ${apiClient.getCount()}")
  }
}
