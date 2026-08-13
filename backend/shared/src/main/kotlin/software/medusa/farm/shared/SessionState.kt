package software.medusa.farm.shared

/** Where a processing session is in its lifecycle. Terminal states are COMPLETED and FAILED. */
enum class SessionState {
  RUNNING,
  COMPLETED,
  FAILED,
}
