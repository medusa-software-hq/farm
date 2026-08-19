package software.medusa.farm.worker

/**
 * A review asking for work, as the agent will be told about it: what the reviewer wrote in the
 * review's own box, and what they wrote on lines. Either may be empty; both being empty means a
 * reviewer asked for changes without saying what, which is still worth acting on.
 */
data class ReviewFeedback(
    val reviewId: Long,
    val body: String,
    val comments: List<ReviewFeedbackComment>,
)
