package software.medusa.farm.claude

/**
 * Claude _engine_, bound to system-level persistent state shared with the `claude` CLI and Claude
 * desktop app.
 */
interface CldEngine {
  /**
   * Runs one session against [prompt] and calls [block] with it, live, for as long as the block
   * lasts. The session is over once the block ends, however it ends.
   *
   * @return Whatever [block] returned.
   * @throws CldAbnormalStartError If the session never got going; [block] is not called at all.
   * @throws CldAbnormalRunError If the session stopped making sense while [block] was running.
   * @throws CldAbnormalExitError If the session did not end the way an ended session should.
   */
  suspend fun <ResultT> runSession(
      config: CldSessionConfig,
      prompt: String,
      block: suspend CldSessionScope.() -> ResultT,
  ): ResultT
}
