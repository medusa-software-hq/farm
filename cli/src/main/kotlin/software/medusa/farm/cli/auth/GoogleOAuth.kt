package software.medusa.farm.cli.auth

import java.net.URI

/**
 * Google's public OAuth 2.0 / OIDC endpoints — the same for every environment (only the client
 * differs).
 */
object GoogleOAuth {
  val AUTH_ENDPOINT: URI = URI.create("https://accounts.google.com/o/oauth2/v2/auth")
  val TOKEN_ENDPOINT: URI = URI.create("https://oauth2.googleapis.com/token")
}
