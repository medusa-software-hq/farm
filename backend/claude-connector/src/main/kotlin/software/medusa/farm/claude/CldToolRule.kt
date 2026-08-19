package software.medusa.farm.claude

/** Claude tool permission rule: one tool, optionally narrowed to a subset of its uses. */
sealed interface CldToolRule {
  /** Reading a file. */
  data object Read : CldToolRule {
    override val expression: String = "Read"
  }

  /** Editing an existing file. */
  data object Edit : CldToolRule {
    override val expression: String = "Edit"
  }

  /** Writing a file whole, creating it if needed. */
  data object Write : CldToolRule {
    override val expression: String = "Write"
  }

  /** Finding files by name pattern. */
  data object Glob : CldToolRule {
    override val expression: String = "Glob"
  }

  /** Searching file contents. */
  data object Grep : CldToolRule {
    override val expression: String = "Grep"
  }

  /** Delegating work to a sub-assistant. */
  data object Task : CldToolRule {
    override val expression: String = "Task"
  }

  /** Fetching a named web page. */
  data object WebFetch : CldToolRule {
    override val expression: String = "WebFetch"
  }

  /** Searching the web. */
  data object WebSearch : CldToolRule {
    override val expression: String = "WebSearch"
  }

  /** Asking the operator a question — which nothing here is able to answer. */
  data object AskUserQuestion : CldToolRule {
    override val expression: String = "AskUserQuestion"
  }

  /** Running a shell command, either any of them or only those matching [commandMask]. */
  data class Bash(
      val commandMask: CommandMask?,
  ) : CldToolRule {
    /** Pattern selecting which commands a [Bash] rule covers. */
    @JvmInline
    value class CommandMask(
        val expression: String,
    )

    override val expression: String
      get() =
          when (commandMask) {
            null -> name
            else -> "$name(${commandMask.expression})"
          }

    companion object {
      internal const val name: String = "Bash"
    }
  }

  /** A tool this library does not know by name. */
  @JvmInline
  value class Unrecognized(
      override val expression: String,
  ) : CldToolRule

  companion object {
    private val knownRules: List<CldToolRule> =
        listOf(Read, Edit, Write, Glob, Grep, Task, WebFetch, WebSearch, AskUserQuestion)

    /**
     * Reads a rule back from its textual form. A form this library does not know becomes
     * [Unrecognized] rather than a failure, so an engine that grows new tools stays readable.
     */
    fun parse(expression: String): CldToolRule =
        knownRules.firstOrNull { it.expression == expression }
            ?: when {
              expression == Bash.name -> Bash(commandMask = null)
              expression.startsWith("${Bash.name}(") && expression.endsWith(")") ->
                  Bash(
                      commandMask =
                          Bash.CommandMask(
                              expression =
                                  expression.removePrefix("${Bash.name}(").removeSuffix(")")
                          )
                  )
              else -> Unrecognized(expression = expression)
            }
  }

  /** Textual form of this rule. */
  val expression: String
}
