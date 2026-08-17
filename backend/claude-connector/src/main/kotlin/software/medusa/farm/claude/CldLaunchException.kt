package software.medusa.farm.claude

/**
 * A failure to *launch* a run — surfaced synchronously from [CldAgent.launch], up to and including
 * the opening `init` handshake. Deliberately opaque: the caller decides what to do with a failed
 * launch (fail the activity, let Temporal re-drive it), not how to read it. The diagnostic detail —
 * the spawn cause, the offending first line — is on [CldReporter], the one place the library keeps
 * debug data, so a public failure never leaks exit codes or stderr strings the caller can't act on.
 */
sealed class CldLaunchException : RuntimeException() {
  // A typed signal, not a diagnostic: the debug data is in CldReporter, and these are singletons,
  // so
  // a captured stack trace would only point at the first throw. Drop it.
  override fun fillInStackTrace(): Throwable = this
}

/** The `claude` binary could not be started at all — a missing or broken installation. */
data object CldCorruptedInstallationException : CldLaunchException()

/** The process started but did not open its stream with the expected `init` handshake. */
data object CldIllegalStartupException : CldLaunchException()
