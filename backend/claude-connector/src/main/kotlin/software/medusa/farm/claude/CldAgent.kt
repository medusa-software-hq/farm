package software.medusa.farm.claude

/**
 * Drives one `claude` run. [run] starts the subprocess, hands [consume] a live [CldRun] — its
 * streaming [CldRun.steps] and terminal [CldRun.result] — and tears the process tree down when
 * [consume] returns, so a run is scoped to that block and cannot leak.
 *
 * The workspace is mutated in place — the connector returns no diff; the caller diffs the
 * directory. Operational failures (missing binary, dead process, timeout) throw
 * [CldConnectorException]; a run the agent itself ends with an error is [CldCompletion.Errored] on
 * the result, not thrown.
 */
interface CldAgent {
  suspend fun <T> run(request: CldRunRequest, consume: suspend (CldRun) -> T): T
}
