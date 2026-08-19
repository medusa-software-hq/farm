package software.medusa.farm.worker

import java.security.SecureRandom

/**
 * Builds what the agent is asked to do.
 *
 * The material is written by whoever filed the issue, which is anyone who can file one — so it is
 * given as a labelled block rather than run together with the instructions around it, and the label
 * carries a nonce that the material cannot have known to forge. Text that tries to close the block
 * early stays inside it, and reads as what it is: something the issue said.
 */
object AgentPrompt {
  fun forIssue(title: String, body: String): String {
    val issue = "$title\n\n${body.ifBlank { "(no description)" }}"

    return """
        |Implement the issue below, in the repository you are working in.
        |
        |${block(name = "ISSUE", content = issue)}
        """
        .trimMargin()
  }

  /**
   * Wraps [content] in markers labelled [name]. The nonce is freshly drawn per prompt, so nothing
   * written before it existed can spell the marker that ends the block.
   */
  private fun block(name: String, content: String): String {
    val nonce = nonce()

    return "===== BEGIN $name $nonce =====\n$content\n===== END $name $nonce ====="
  }

  private fun nonce(): String {
    val bytes = ByteArray(NONCE_BYTES)
    RANDOM.nextBytes(bytes)

    return bytes.joinToString(separator = "") { "%02x".format(it) }
  }

  private const val NONCE_BYTES = 8

  private val RANDOM = SecureRandom()
}
