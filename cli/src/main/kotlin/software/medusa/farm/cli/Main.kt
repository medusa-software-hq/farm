package software.medusa.farm.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import software.medusa.farm.cli.command.DecrementCommand
import software.medusa.farm.cli.command.GetCommand
import software.medusa.farm.cli.command.IncrementCommand
import software.medusa.farm.cli.command.LinkOrgCommand
import software.medusa.farm.cli.command.LoginCommand
import software.medusa.farm.cli.command.LogoutCommand

class MainCommand : NoOpCliktCommand(name = "ms-farm") {
  override fun help(context: Context) =
      "Drive the Farm service from a terminal, authenticated with your Google sign-in."
}

fun main(args: Array<String>) {
  // Each command is self-contained: it resolves the environment, opens its config, and (for API
  // commands) builds the authenticated client — see AppCommand / ManagementCommand.
  MainCommand()
      .subcommands(
          LoginCommand(),
          LogoutCommand(),
          IncrementCommand(),
          DecrementCommand(),
          GetCommand(),
          LinkOrgCommand(),
      )
      .main(args)
}
