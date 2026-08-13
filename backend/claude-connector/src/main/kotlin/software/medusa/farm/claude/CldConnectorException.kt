package software.medusa.farm.claude

/**
 * An operational failure of the connector itself — the binary is missing, the process died without
 * a result, or the wall-clock guard tripped. These are distinct from a *domain* outcome (the agent
 * ran and reported error or gave up): operational failures are meant to propagate so the caller's
 * retry layer (Temporal) can re-drive the whole activity.
 */
class CldConnectorException
private constructor(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause) {
  companion object {
    fun binaryUnavailable(cause: Throwable): CldConnectorException =
        CldConnectorException("The `claude` executable could not be started.", cause)

    fun timedOut(): CldConnectorException =
        CldConnectorException("The `claude` run exceeded its wall-clock budget.", cause = null)

    fun diedWithoutResult(exitCode: Int, standardError: String): CldConnectorException =
        CldConnectorException(
            "The `claude` process exited $exitCode without a result. " +
                "stderr: ${standardError.takeLast(stderrTail)}",
            cause = null,
        )

    private const val stderrTail = 2_000
  }
}
