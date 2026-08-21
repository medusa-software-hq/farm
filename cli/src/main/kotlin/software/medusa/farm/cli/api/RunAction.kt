package software.medusa.farm.cli.api

/** Something the agent did with a tool, as the one thing worth knowing about it. */
sealed interface RunAction {
  data class Edited(val path: String) : RunAction

  data class Read(val path: String) : RunAction

  data class Ran(val command: String) : RunAction

  data class Searched(val query: String) : RunAction

  /** A tool outside the set worth naming, carrying its own name. */
  data class Used(val tool: String) : RunAction

  /** An action of no kind at all, which is what an unset one is. */
  data object Unknown : RunAction
}
