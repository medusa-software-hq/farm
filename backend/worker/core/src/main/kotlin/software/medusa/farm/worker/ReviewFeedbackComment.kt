package software.medusa.farm.worker

/** One thing a reviewer said about a line, and where they said it. */
data class ReviewFeedbackComment(
    val path: String,
    /** Null where the comment has outlived the line it was written on. */
    val line: Int?,
    val body: String,
)
