package software.medusa.farm.github

/**
 * A GitHub App's private key, as an unencrypted PKCS#8 PEM.
 *
 * Checked on the way in rather than where it is used. What arrives here is whatever a deployment
 * put in a secret, and the two ways of getting that wrong — a value that is not a key at all, and
 * the PKCS#1 key GitHub actually issues — both used to surface as a base64 complaint about a
 * character, which says nothing about either.
 *
 * Never says what it was given. The value is a private key, and the message goes to a log.
 */
@JvmInline
value class GhAppPrivateKey(val pem: String) {
  init {
    require(pem.contains(PKCS8_BEGIN) && pem.contains(PKCS8_END)) {
      if (pem.contains(PKCS1_BEGIN)) {
        "This is the PKCS#1 key GitHub issues. It has to be converted to PKCS#8 first — see the " +
            "github-client README — and the converted key is what belongs in the secret."
      } else {
        "Not a private key: expected a $PKCS8_BEGIN block. A secret that was never given its " +
            "value looks like this too."
      }
    }
  }

  companion object {
    const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"

    const val PKCS8_END = "-----END PRIVATE KEY-----"

    private const val PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----"
  }
}
