package software.medusa.farm.summary

/**
 * The result of summarizing a run. [Unavailable] is explicit — the summary is best-effort
 * orientation, so a failed or empty model response is distinguishable from a real summary (never an
 * empty string). The failure detail is logged by the client's reporter, not carried here.
 */
sealed interface RunSummary {
  data class Available(val text: String) : RunSummary

  data object Unavailable : RunSummary
}
