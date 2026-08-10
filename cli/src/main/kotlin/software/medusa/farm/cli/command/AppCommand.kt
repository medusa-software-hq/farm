package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import java.nio.file.Path
import software.medusa.farm.cli.api.ApiException
import software.medusa.farm.cli.auth.NotLoggedInException
import software.medusa.farm.cli.config.ConfigBaseDir
import software.medusa.farm.cli.config.ConfigStore
import software.medusa.farm.cli.config.Environment
import software.medusa.farm.cli.config.EnvironmentSelectionException

/**
 * Base for every ms-farm command. Resolves the environment once (the AWS_PROFILE-style
 * FARM_ENVIRONMENT read), announces a non-prod session on stderr, opens its ConfigStore, and maps
 * the two expected failures to clean CLI messages — so a subclass implements only [run] against a
 * resolved [Environment] and its [ConfigStore].
 */
abstract class AppCommand(name: String) : CliktCommand(name = name) {
  final override fun run() {
    val environment =
        try {
          Environment.current(
              System.getenv(Environment.ENV_VAR),
              System.getenv(Environment.LOCAL_CONFIG_PATH_ENV),
              System.getenv(Environment.LOCAL_PORT_ENV),
          )
        } catch (e: EnvironmentSelectionException) {
          throw PrintMessage(e.message ?: "Bad environment.", statusCode = 2, printError = true)
        }

    // Non-prod sessions announce themselves on stderr so a human can't mix environments.
    environment.marker?.let { echo(dimmedForStderr(it), err = true) }

    val configStore = ConfigStore(environment.resolveConfigDirPath(farmConfigBase()))

    try {
      run(environment, configStore)
    } catch (e: NotLoggedInException) {
      throw PrintMessage(e.message ?: "Not signed in.", statusCode = 1, printError = true)
    } catch (e: ApiException) {
      throw PrintMessage(e.message ?: "API error.", statusCode = 1, printError = true)
    }
  }

  abstract fun run(environment: Environment, configStore: ConfigStore)

  private fun farmConfigBase(): Path =
      ConfigBaseDir.resolve(System.getenv("XDG_CONFIG_HOME"), System.getProperty("user.home"))
}

/** [text] wrapped in ANSI dim, but only when stderr is an interactive terminal (else plain). */
private fun dimmedForStderr(text: String): String =
    if (System.console() != null) "[2m$text[22m" else text
