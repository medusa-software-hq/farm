package software.medusa.farm.github

/**
 * A request GitHub did not answer with what was asked for, carrying enough to tell apart the two
 * reasons a caller cares about.
 *
 * A refusal is GitHub declining to answer: this token may not ask, or this repository's plan does
 * not include the answer. Asking again changes nothing, and a caller that can live without the
 * answer may carry on. Everything else — a rate limit, a fault, a bad credential — is GitHub
 * failing to answer, and carrying on would be pretending to know something.
 */
class GhRequestFailed(
    val statusCode: Int,
    val body: String,
    /** Whether the refusal was for spending an allowance rather than for asking at all. */
    val rateLimited: Boolean,
    message: String,
) : IllegalStateException(message) {
  /** Whether GitHub declined to answer, as opposed to failing to. */
  val refused: Boolean
    get() = statusCode == FORBIDDEN && !rateLimited

  companion object {
    private const val FORBIDDEN = 403

    /**
     * A spent allowance says so in the headers rather than in the status: a 403 is 403 either way,
     * and only `x-ratelimit-remaining: 0` or a `retry-after` tells them apart.
     */
    fun rateLimitedBy(headers: Map<String, List<String>>): Boolean {
      val remaining = headers.entries.firstOrNull { it.key.equals(REMAINING, ignoreCase = true) }
      val retryAfter = headers.keys.any { it.equals(RETRY_AFTER, ignoreCase = true) }

      return retryAfter || remaining?.value?.firstOrNull() == "0"
    }

    private const val REMAINING = "x-ratelimit-remaining"
    private const val RETRY_AFTER = "retry-after"
  }
}
