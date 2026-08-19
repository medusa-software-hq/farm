package software.medusa.farm.claude

/**
 * The engine behaved in a way this library is unwilling to make sense of, so the session it was
 * asked for cannot be delivered.
 *
 * An error is deliberately opaque: what a caller can do about a broken engine does not depend on
 * which way it broke. The detail that says which way goes to [CldAnomalyReporter] instead. A
 * session the assistant itself ends badly is not one of these — that is a [CldRunStatus.Error] on
 * the result.
 */
sealed class CldError : Exception() {
  override fun fillInStackTrace(): Throwable = this
}

/** The session never got going. */
@Suppress("ObjectInheritsException", "JavaIoSerializableObjectMustHaveReadResolve")
data object CldAbnormalStartError : CldError()

/** The session got going, and then stopped making sense. */
@Suppress("ObjectInheritsException", "JavaIoSerializableObjectMustHaveReadResolve")
data object CldAbnormalRunError : CldError()

/** The session did not end the way an ended session is supposed to. */
@Suppress("ObjectInheritsException", "JavaIoSerializableObjectMustHaveReadResolve")
data object CldAbnormalExitError : CldError()
