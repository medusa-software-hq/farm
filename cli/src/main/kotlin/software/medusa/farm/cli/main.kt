package software.medusa.farm.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import kotlin.system.exitProcess

class MainCommand : NoOpCliktCommand(name = "ms-farm") {
  override fun help(context: Context) =
      "Increment, decrement, and read the counter, authenticated with your Google sign-in."
}

fun main(args: Array<String>) {
  // Read-once composition root: resolve the environment from COUNTER_ENVIRONMENT exactly here, then
  // inject it via the Clikt context so no command reads that variable again.
  val environment =
      try {
        Environment.current()
      } catch (e: EnvironmentSelectionException) {
        System.err.println(e.message)
        exitProcess(2)
      }

  // Non-prod sessions announce themselves on stderr (partitioned state dirs prevent *state* mixing;
  // this prevents *human* mixing). Dim when stderr is a terminal; plain otherwise.
  environment.marker?.let { System.err.println(dimmedForStderr(it)) }

  MainCommand()
      .context { obj = environment }
      .subcommands(
          LoginCommand(),
          LogoutCommand(),
          IncrementCommand(),
          DecrementCommand(),
          GetCommand(),
      )
      .main(args)
}

/**
 * An authenticated client for this environment (its endpoint + its cached, silently-refreshed
 * token).
 */
private fun Environment.apiClient(): CounterApiClient =
    CounterApiClient(
        apiBaseUrl,
        idTokenProvider = {
          Session(dir = configDir, refresher = defaultRefresher(this)).currentIdToken()
        },
    )

/** Turns the two expected failures into clean, actionable CLI errors. */
private inline fun <T> runCounter(block: () -> T): T =
    try {
      block()
    } catch (e: NotLoggedInException) {
      throw PrintMessage(e.message ?: "Not signed in.", statusCode = 1, printError = true)
    } catch (e: ApiException) {
      throw PrintMessage(e.message ?: "API error.", statusCode = 1, printError = true)
    }

/** `login` — the loopback + PKCE browser sign-in; caches the refresh token for this environment. */
class LoginCommand : CliktCommand(name = "login") {
  private val env by requireObject<Environment>()

  override fun help(context: Context) =
      "Sign in with your medusa.software Google account and cache the session."

  override fun run() {
    val secret =
        env.oauthClientSecret
            ?: throw PrintMessage(
                "This CLI build has no OAuth client secret for ${env.label} and " +
                    "${env.oauthClientSecretEnvVar} is not set. Install a released build, or set " +
                    "that env var for a local build.",
                statusCode = 1,
                printError = true,
            )

    val tokens =
        try {
          CounterOAuth(clientId = env.oauthClientId, clientSecret = secret)
              .login(echo = { echo(it) })
        } catch (e: OAuthException) {
          throw PrintMessage("Sign-in failed: ${e.message}", statusCode = 1, printError = true)
        }

    val refreshToken =
        tokens.refreshToken
            ?: throw PrintMessage(
                "Google did not return a refresh token, so the session can't be cached. Try again.",
                statusCode = 1,
                printError = true,
            )
    val email = Jwt.email(tokens.idToken) ?: "unknown"
    saveCredentials(
        Credentials(refreshToken, tokens.idToken, tokens.expiresAtEpochSec, email),
        env.configDir,
    )
    echo("Signed in as $email")
  }
}

/** `logout` — forget the cached session for this environment. */
class LogoutCommand : CliktCommand(name = "logout") {
  private val env by requireObject<Environment>()

  override fun help(context: Context) = "Forget the cached session on this machine."

  override fun run() {
    deleteCredentials(env.configDir)
    echo("Signed out.")
  }
}

class IncrementCommand : CliktCommand(name = "increment") {
  private val env by requireObject<Environment>()

  override fun help(context: Context) = "Increment the counter and print the new value."

  override fun run() = runCounter { echo("Count: ${env.apiClient().increment()}") }
}

class DecrementCommand : CliktCommand(name = "decrement") {
  private val env by requireObject<Environment>()

  override fun help(context: Context) = "Decrement the counter and print the new value."

  override fun run() = runCounter { echo("Count: ${env.apiClient().decrement()}") }
}

class GetCommand : CliktCommand(name = "get") {
  private val env by requireObject<Environment>()

  override fun help(context: Context) = "Print the current counter value."

  override fun run() = runCounter { echo("Count: ${env.apiClient().getCount()}") }
}
