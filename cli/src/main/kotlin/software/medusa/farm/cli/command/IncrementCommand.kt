package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import software.medusa.farm.cli.api.FarmApiClient

class IncrementCommand : ManagementCommand(name = "increment") {
  override fun help(context: Context) = "Increment the counter and print the new value."

  override fun run(apiClient: FarmApiClient) {
    echo("Count: ${apiClient.increment()}")
  }
}
