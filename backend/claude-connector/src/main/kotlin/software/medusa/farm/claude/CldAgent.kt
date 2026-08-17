package software.medusa.farm.claude

/**
 * Drives `claude`. [run] starts the subprocess, waits for its opening `init` handshake — the run
 * "starts" when claude speaks its protocol, not when the OS process does — and then calls [body]
 * with a live [CldRunScope], returning whatever the body returns. The process tree is killed when
 * the body ends, however it ends, so there is no handle to leak and nothing to close.
 *
 * The workspace is mutated in place — the connector returns no diff; the caller diffs the
 * directory.
 *
 * Operational failures are opaque and all surface from this one call, which is the only place a
 * caller handles them. They are split by phase: [CldLaunchException] means the run never started
 * and [body] was never entered; [CldRunException] means it started and then died, cancelling [body]
 * wherever it had got to. A run the agent itself ends with an error is neither — that is
 * [CldCompletion.Errored] on the result.
 *
 * @throws CldCorruptedInstallationException if the `claude` binary could not be started.
 * @throws CldIllegalStartupException if it started but did not open with an `init` handshake.
 * @throws CldIllegalRunException if the stream broke mid-run.
 * @throws CldIllegalExitException if the process exited without its terminal `result`.
 */
interface CldAgent {
  suspend fun <T> run(request: CldRunRequest, body: suspend CldRunScope.() -> T): T
}
