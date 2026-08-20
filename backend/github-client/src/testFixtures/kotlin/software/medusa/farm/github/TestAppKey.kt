package software.medusa.farm.github

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * A throwaway RSA app key: a PKCS#8 PEM to feed the client, plus the public half to verify JWTs.
 */
class TestAppKey {
  private val keyPair =
      KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

  val publicKey: RSAPublicKey = keyPair.public as RSAPublicKey

  val pkcs8Pem: GhAppPrivateKey =
      GhAppPrivateKey(
          buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            append(
                Base64.getMimeEncoder(64, "\n".toByteArray())
                    .encodeToString(keyPair.private.encoded)
            )
            append("\n-----END PRIVATE KEY-----\n")
          }
      )
}
