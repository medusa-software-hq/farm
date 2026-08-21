package software.medusa.farm.cli.api

/**
 * A processing session as Farm records it, carrying the issue it was opened for as that issue was
 * at the time. [state] is a string rather than something narrower because the service treats it as
 * one; [sessionStateDescription] is where it is given a meaning.
 */
class SessionResult(
    val id: String,
    val repoFullName: String,
    val number: Int,
    val title: String,
    val state: String,
    /** Empty until a run opens one. */
    val pullRequestUrl: String,
)
