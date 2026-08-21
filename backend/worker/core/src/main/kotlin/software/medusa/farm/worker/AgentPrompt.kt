package software.medusa.farm.worker

import java.security.SecureRandom

/**
 * Builds what the agent is asked to do.
 *
 * The material is written by whoever filed the issue, reviewed the pull request, or wrote the check
 * that reported on it — so it is given as labelled blocks rather than run together with the
 * instructions around it, and the labels carry a nonce that the material cannot have known to
 * forge. Text that tries to close a block early stays inside it, and reads as what it is: something
 * the issue, the review or the check said.
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

  /**
   * The prompt for a run putting [failedChecks] right. As with [forFixup] the session is a fresh
   * one, so what the run being followed up did comes back as [previousSummary].
   */
  fun forFailedChecks(
      title: String,
      body: String,
      previousSummary: String,
      failedChecks: List<FailedCheck>,
  ): String {
    val nonce = nonce()

    return """
        |Make the failing checks below pass, in the repository you are working in. The work they ran
        |against is already committed to the branch you are on; fix what they are failing on and
        |leave the rest alone.
        |
        |$READ_THE_REPOSITORY_FIRST
        |
        |${block(nonce, name = "ISSUE", content = issue(title, body))}
        |
        |${block(nonce, name = "WHAT THE PREVIOUS RUN DID", content = previousSummary)}
        |
        |${block(nonce, name = "FAILING CHECKS", content = render(failedChecks))}
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
   * The checks as prose: each one named, then what it reported, then the lines it pointed at. A
   * check that reported nothing at all is still named, which is the whole of what it left behind.
   */
  private fun render(failedChecks: List<FailedCheck>): String =
      failedChecks.joinToString(separator = "\n\n") { check ->
        buildList {
              add("The check \"${check.name}\" failed.")
              if (check.report.isNotBlank()) add(check.report.trim())

              check.annotations.forEach { annotation ->
                val where = annotation.line?.let { "${annotation.path}:$it" } ?: annotation.path
                add("$where\n${annotation.message.trim()}")
              }
            }
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
