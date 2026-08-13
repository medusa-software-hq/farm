package software.medusa.farm.claude

/** How a run picks up a `claude` session: start a new one, or resume a snapshotted one. */
sealed interface CldSessionSelector {
  /** The CLI [sessionId] this run runs under; the resume anchor to snapshot afterwards. */
  val sessionId: String

  /** Start a fresh session under a caller-minted [sessionId] (passed as `--session-id`). */
  data class Fresh(override val sessionId: String) : CldSessionSelector

  /** Resume the session captured by [from] (passed as `--resume`). */
  data class Resume(val from: CldSessionRef) : CldSessionSelector {
    override val sessionId: String
      get() = from.sessionId
  }
}
