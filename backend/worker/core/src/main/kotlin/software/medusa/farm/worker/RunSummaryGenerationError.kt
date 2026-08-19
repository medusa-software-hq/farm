package software.medusa.farm.worker

/**
 * The backend would not produce a summary, so the run it was asked about cannot be described.
 *
 * One case, not several: nothing a caller does about a summary it cannot get depends on why it
 * could not get one. Which way it failed is logged where the failure is recognized.
 */
@Suppress("ObjectInheritsException", "JavaIoSerializableObjectMustHaveReadResolve")
data object RunSummaryGenerationError : Exception() {
  // A typed signal, not a diagnostic: this is a singleton, so a captured stack trace would only
  // point at the first throw. Drop it.
  override fun fillInStackTrace(): Throwable = this
}
