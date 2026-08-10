package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import software.medusa.farm.cli.api.FarmApiClient

class DecrementCommand : ManagementCommand(name = "decrement") {
  override fun help(context: Context) = "Decrement the counter and print the new value."

  override fun run(apiClient: FarmApiClient) {
    echo("Count: ${apiClient.decrement()}")
  }
}
