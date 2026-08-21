package software.medusa.farm.worker

/**
 * A check that came back red, as the agent will be told about it: what it is called, what it
 * reported, and the places in the code it pointed at.
 *
 * Two of these are equal when a check has failed the same way twice, which is what the run that
 * would otherwise be spent on it again is refused by.
 */
data class FailedCheck(
    val name: String,
    /** What the check reported as a whole; empty when it reported only annotations. */
    val report: String,
    val annotations: List<FailedCheckAnnotation>,
)
