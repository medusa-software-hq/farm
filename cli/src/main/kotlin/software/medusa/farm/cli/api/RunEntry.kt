package software.medusa.farm.cli.api

/** One thing that happened during a try, in the order it happened. */
sealed interface RunEntry {
  /** What the agent said, and what it did while saying it. */
  data class Step(val text: String, val actions: List<RunAction>) : RunEntry

  /** Something the agent's backend said outside its account of the work. */
  data class Warning(val text: String) : RunEntry
}
