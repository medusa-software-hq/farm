package software.medusa.farm.cli.auth

/**
 * Supplies a currently-valid Google ID token to present as the bearer credential on an API call.
 */
interface IdTokenProvider {
  fun provideFreshIdToken(): String
}
