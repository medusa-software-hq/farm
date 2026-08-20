package software.medusa.farm.worker

import java.security.SecureRandom

/**
 * Builds what the agent is asked to do.
 *
 * The material is written by whoever filed the issue or reviewed the pull request, which is anyone
 * who can do either — so it is given as labelled blocks rather than run together with the
 * instructions around it, and the labels carry a nonce that the material cannot have known to
 * forge. Text that tries to close a block early stays inside it, and reads as what it is: something
 * the issue or the review said.
 */
object AgentPrompt {
  fun forIssue(title: String, body: String): String {
    val nonce = nonce()

    return """
        |Implement the issue below, in the repository you are working in.
        |
        |$READ_THE_REPOSITORY_FIRST
        |
        |${block(nonce, name = "ISSUE", content = issue(title, body))}
        """
        .trimMargin()
  }

  /**
   * The prompt for a run addressing [feedback]. The session is a fresh one — it remembers nothing
   * of the run being followed up — so what that run did comes back as [previousSummary] rather than
   * as history the agent still holds.
   */
  fun forFixup(
      title: String,
      body: String,
      previousSummary: String,
      feedback: ReviewFeedback,
  ): String {
    val nonce = nonce()

    return """
        |Address the review feedback below, in the repository you are working in. The work it is
        |about is already committed to the branch you are on; change what the review asks for and
        |leave the rest alone.
        |
        |$READ_THE_REPOSITORY_FIRST
        |
        |${block(nonce, name = "ISSUE", content = issue(title, body))}
        |
        |${block(nonce, name = "WHAT THE PREVIOUS RUN DID", content = previousSummary)}
        |
        |${block(nonce, name = "REVIEW FEEDBACK", content = render(feedback))}
        """
        .trimMargin()
  }

  private fun issue(title: String, body: String): String =
      "$title\n\n${body.ifBlank { "(no description)" }}"

  /** The review as prose: what was said about the whole thing, then what was said about lines. */
  private fun render(feedback: ReviewFeedback): String {
    val parts = buildList {
      if (feedback.body.isNotBlank()) add(feedback.body.trim())

      feedback.comments.forEach { comment ->
        val where = comment.line?.let { "${comment.path}:$it" } ?: comment.path
        add("$where\n${comment.body.trim()}")
      }
    }

    // A reviewer can ask for changes without typing anything anywhere, and the agent still has to
    // be told something rather than an empty block.
    return parts
        .ifEmpty { listOf("Changes were requested without any comment.") }
        .joinToString(separator = "\n\n")
  }

  /**
   * Wraps [content] in markers labelled [name]. The nonce is drawn per prompt, so nothing written
   * before it existed can spell the marker that ends a block.
   */
  private fun block(nonce: String, name: String, content: String): String =
      "===== BEGIN $name $nonce =====\n$content\n===== END $name $nonce ====="

  private fun nonce(): String {
    val bytes = ByteArray(NONCE_BYTES)
    RANDOM.nextBytes(bytes)

    return bytes.joinToString(separator = "") { "%02x".format(it) }
  }

  // A repository says how it wants to be worked in — how to build it, what its checks are, what it
  // will send a change back for. None of that is knowable from the issue, and a run that guesses at
  // it writes a pull request that fails the repository's own checks.
  private val READ_THE_REPOSITORY_FIRST =
      """
      |Read the repository first: its README, and whatever guidance it keeps for people working in
      |it — AGENTS.md, CLAUDE.md, a contributing guide, a docs directory. Build and check your work
      |the way it says to, and follow the conventions it asks for. Where it says nothing, follow
      |what the surrounding code already does.
      """
          .trimMargin()

  private const val NONCE_BYTES = 8

  private val RANDOM = SecureRandom()
}
