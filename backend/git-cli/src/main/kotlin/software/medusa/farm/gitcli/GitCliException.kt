package software.medusa.farm.gitcli

/** A `git` invocation exited non-zero. Carries the failing subcommand and git's combined output. */
class GitCliException(
    val command: String,
    val exitCode: Int,
    val output: String,
) : RuntimeException("git $command failed (exit $exitCode):\n$output")
