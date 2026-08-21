package software.medusa.farm.cli.api

/** One try at a run: what it did, and how it ended if it has. */
class RunAttemptResult(
    val number: Int,
    /** "RUNNING", "ABANDONED", or "FINISHED". */
    val state: String,
    val entries: List<RunEntry>,
    /** Null until [state] is FINISHED. */
    val outcome: RunOutcomeResult?,
)
