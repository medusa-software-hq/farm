package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import software.medusa.farm.cli.auth.GoogleOAuth
import software.medusa.farm.cli.auth.JwtToken
import software.medusa.farm.cli.auth.OAuthException
import software.medusa.farm.cli.auth.OAuthTokenClient
import software.medusa.farm.cli.auth.SystemBrowserOpener
import software.medusa.farm.cli.auth.googleSignIn
import software.medusa.farm.cli.config.ConfigStore
import software.medusa.farm.cli.config.Credentials
import software.medusa.farm.cli.config.Environment

/** `login` — the loopback + PKCE browser sign-in; caches the refresh token for this environment. */
class LoginCommand : AppCommand(name = "login") {
  override fun help(context: Context) =
      "Sign in with your medusa.software Google account and cache the session."

  override fun run(environment: Environment, configStore: ConfigStore) {
    val clientId = environment.oauthClientId
    val tokenClient =
        OAuthTokenClient(GoogleOAuth.TOKEN_ENDPOINT, clientId, environment.oauthClientSecret)
    val tokens =
        try {
          googleSignIn(
              GoogleOAuth.AUTH_ENDPOINT,
              clientId,
              tokenClient,
              SystemBrowserOpener,
              echo = { echo(it) },
          )
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
    val email = JwtToken.parse(tokens.idToken).email ?: "unknown"
    configStore.saveCredentials(Credentials(refreshToken, tokens.idToken, tokens.expiresAt, email))
    echo("Signed in as $email")
  }
}
