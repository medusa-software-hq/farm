package software.medusa.farm.cli.api

/** How a try ended, once it has. */
class RunOutcomeResult(
    /** "SUCCEEDED" or "ERRORED". */
    val outcome: String,
    val summary: String,
    /** Null where the agent's backend reported no cost. */
    val usd: Double?,
)
