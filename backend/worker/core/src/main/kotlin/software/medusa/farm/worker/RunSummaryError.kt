package software.medusa.farm.worker

/**
 * The backend would not produce a summary, so the run it was asked about cannot be described.
 *
 * Opaque by design: what a caller can do about a backend that will not answer does not depend on
 * how it declined. The detail that says how goes to the reporter instead. A summary is a required
 * part of what the system records about a run, so there is no outcome here that means "carry on
 * without one" — the caller retries or fails.
 */
sealed class RunSummaryError : Exception() {
  // A typed signal, not a diagnostic: the debug data is with the reporter, and these are
  // singletons,
  // so a captured stack trace would only point at the first throw. Drop it.
  override fun fillInStackTrace(): Throwable = this
}

/** The backend could not be reached, or would not answer. */
@Suppress("ObjectInheritsException", "JavaIoSerializableObjectMustHaveReadResolve")
data object RunSummaryBackendUnreachableError : RunSummaryError()

/** The backend answered, but with nothing that could be used as a summary. */
@Suppress("ObjectInheritsException", "JavaIoSerializableObjectMustHaveReadResolve")
data object RunSummaryEmptyAnswerError : RunSummaryError()
