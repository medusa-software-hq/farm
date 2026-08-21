package software.medusa.farm.github

/** One place in the code a check run pointed at, and what it said there. */
data class GhCheckAnnotation(
    val path: String,
    /** Null where the check pointed at the file rather than at a line of it. */
    val startLine: Int?,
    val message: String,
)
