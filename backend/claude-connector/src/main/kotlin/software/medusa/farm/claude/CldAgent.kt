package software.medusa.farm.claude

/**
 * Drives `claude`. [launch] starts the subprocess and returns a live [CldRun] — its streaming
 * [CldRun.steps] and terminal [CldRun.result] — which the caller must [CldRun.close] (a `use { }`
 * block is the intended pattern).
 *
 * The workspace is mutated in place — the connector returns no diff; the caller diffs the
 * directory. Operational failures (missing binary, dead process, timeout) surface as
 * [CldConnectorException] — thrown by [launch] for a missing binary, otherwise failing
 * [CldRun.result]; a run the agent itself ends with an error is [CldCompletion.Errored] on the
 * result, not a failure.
 */
interface CldAgent {
  fun launch(request: CldRunRequest): CldRun
}
