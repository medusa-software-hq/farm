package software.medusa.farm.claude

/**
 * A failure *during* a run — thrown out of [CldAgent.run] once the launch already succeeded, so it
 * cancels the block wherever it had got to rather than waiting to be asked about. Like
 * [CldLaunchException] it is opaque by design; the diagnostic detail is on [CldReporter]. A run the
 * agent itself ends with an error is not this — that is [CldCompletion.Errored] on the result.
 */
sealed class CldRunException : RuntimeException() {
  override fun fillInStackTrace(): Throwable = this
}

/** The stream broke mid-run — stdout could not be read or parsed through to the terminal result. */
data object CldIllegalRunException : CldRunException()

/** The process exited without ever emitting its terminal `result`. */
data object CldIllegalExitException : CldRunException()
