package software.medusa.farm.cli.auth

/**
 * Raised for any failure in the OAuth exchange — carries the OAuth `error` code where there is one.
 */
class OAuthException(val code: String, val detail: String?) :
    Exception("OAuth failed: $code" + (detail?.let { " ($it)" } ?: ""))
