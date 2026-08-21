package software.medusa.farm.github

/** One check run against a commit: what it is called, where it has got to, and what it reported. */
data class GhCheckRun(
    val id: GhCheckRunId,
    /** The name a branch protection rule names it by, so two runs of one check share it. */
    val name: String,
    val status: GhCheckRunStatus,
    /** Null until the run has completed. */
    val conclusion: GhCheckRunConclusion?,
    val output: GhCheckRunOutput,
)
