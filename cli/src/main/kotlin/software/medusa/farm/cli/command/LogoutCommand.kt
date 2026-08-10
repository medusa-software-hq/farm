package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import software.medusa.farm.cli.config.ConfigStore
import software.medusa.farm.cli.config.Environment

/** `logout` — forget the cached session for this environment. */
class LogoutCommand : AppCommand(name = "logout") {
  override fun help(context: Context) = "Forget the cached session on this machine."

  override fun run(environment: Environment, configStore: ConfigStore) {
    configStore.deleteCredentials()
    echo("Signed out.")
  }
}
