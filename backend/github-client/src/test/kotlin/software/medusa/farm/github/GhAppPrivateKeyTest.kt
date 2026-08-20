package software.medusa.farm.github

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class GhAppPrivateKeyTest {
  @Test
  fun `the key GitHub issues is refused, and said to be the one GitHub issues`() {
    // The likeliest way to get this wrong: GitHub hands out PKCS#1, and it reads like a private key
    // to anyone who is not a parser.
    val failure =
        assertFailsWith<IllegalArgumentException> {
          GhAppPrivateKey("-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----")
        }

    assertContains(failure.message.orEmpty(), "PKCS#1")
  }

  @Test
  fun `a secret that was never given its value is refused as such`() {
    val failure = assertFailsWith<IllegalArgumentException> { GhAppPrivateKey("set-out-of-band") }

    assertContains(failure.message.orEmpty(), "never given its value")
  }

  @Test
  fun `what it is given is never in what it says`() {
    // The message goes to a log; the value is a private key.
    val failure =
        assertFailsWith<IllegalArgumentException> { GhAppPrivateKey("hunter2-in-a-secret") }

    assertContains(failure.message.orEmpty(), "Not a private key")
    assert(!failure.message.orEmpty().contains("hunter2")) { "the key was in the message" }
  }

  @Test
  fun `a PKCS8 key is taken`() {
    GhAppPrivateKey("-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----")
  }
}
