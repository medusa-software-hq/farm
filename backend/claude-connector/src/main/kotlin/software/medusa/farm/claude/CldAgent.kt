package software.medusa.farm.claude

/**
 * Drives `claude`. [launch] starts the subprocess and waits for its opening `init` handshake — the
 * run "starts" when claude speaks its protocol, not when the OS process does — then returns a live
 * [CldRun] whose [CldRun.steps] stream and [CldRun.result] completes as the body arrives (like HTTP
 * headers, then body). The caller must [CldRun.close] it (a `use { }` block is the intended
 * pattern).
 *
 * The workspace is mutated in place — the connector returns no diff; the caller diffs the
 * directory. Operational failures surface as [CldConnectorException] — thrown by [launch] up to and
 * including the handshake (missing binary, no `init`), and afterwards via [CldRun.result] (dead
 * process, timeout); a run the agent itself ends with an error is [CldCompletion.Errored] on the
 * result, not a failure.
 */
interface CldAgent {
  suspend fun launch(request: CldRunRequest): CldRun
}
