package software.medusa.farm.cli.api

/** One run within a session: ordinal 0 is the work on the issue, 1 and up are fixups. */
class RunResult(
    val ordinal: Int,
    val attempts: List<RunAttemptResult>,
)
