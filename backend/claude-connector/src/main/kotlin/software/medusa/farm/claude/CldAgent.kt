package software.medusa.farm.claude

/**
 * Drives one `claude` run to completion: builds the CLI invocation, streams each parsed message to
 * [onMessage] as it arrives, and returns the run's [CldRunResult].
 *
 * The workspace is mutated in place — the connector returns no diff; the caller diffs the
 * directory. Operational failures (missing binary, dead process, timeout) throw
 * [CldConnectorException] so the caller's retry layer can re-drive; a run the agent itself ends
 * with an error is reported as [CldCompletion.Errored], not thrown.
 */
interface CldAgent {
  suspend fun run(request: CldRunRequest, onMessage: (CldMessage) -> Unit): CldRunResult
}
