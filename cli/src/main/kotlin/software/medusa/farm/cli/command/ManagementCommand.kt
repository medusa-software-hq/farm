package software.medusa.farm.cli.command

import kotlin.time.Clock
import software.medusa.farm.cli.api.FarmApiClient
import software.medusa.farm.cli.auth.ConfigIdTokenProvider
import software.medusa.farm.cli.auth.GoogleOAuth
import software.medusa.farm.cli.auth.NotLoggedInException
import software.medusa.farm.cli.auth.OAuthTokenClient
import software.medusa.farm.cli.config.ConfigStore
import software.medusa.farm.cli.config.Environment

/**
 * Base for commands that talk to the farm API. Builds the authenticated FarmApiClient for the
 * resolved environment — resolving the OAuth secret and loading the cached sign-in (else failing
 * with a 'run login' hint) — and runs [run] against it, closing the client afterward.
 */
abstract class ManagementCommand(name: String) : AppCommand(name = name) {
  final override fun run(environment: Environment, configStore: ConfigStore) {
    val tokenClient =
        OAuthTokenClient(
            GoogleOAuth.TOKEN_ENDPOINT,
            environment.oauthClientId,
            environment.oauthClientSecret,
        )
    val idTokenProvider =
        ConfigIdTokenProvider.load(Clock.System, configStore, tokenClient)
            ?: throw NotLoggedInException("Not signed in. Run 'ms-farm login' first.")

    FarmApiClient(environment.apiEndpoint, idTokenProvider).use { run(it) }
  }

  abstract fun run(apiClient: FarmApiClient)
}
