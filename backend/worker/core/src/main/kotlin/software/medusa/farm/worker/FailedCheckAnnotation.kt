package software.medusa.farm.worker

/** One place a failing check pointed at, and what it said there. */
data class FailedCheckAnnotation(
    val path: String,
    /** Null where the check pointed at the file rather than at a line of it. */
    val line: Int?,
    val message: String,
)
