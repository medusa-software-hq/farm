package software.medusa.farm.claude

/** How a session ended, as the assistant itself reported it. */
sealed interface CldRunStatus {
  /** The assistant finished the work it was given. */
  data object Success : CldRunStatus

  /** The assistant stopped short of finishing. */
  sealed interface Error : CldRunStatus {
    /** It ran out of the turns it was allowed to take. */
    data object TurnLimitReached : Error {
      override val type: String = "error_max_turns"
    }

    /** It ran out of the money it was allowed to spend. */
    data object SpendBudgetReached : Error {
      override val type: String = "error_max_budget_usd"
    }

    /** It broke down partway through. */
    data object Critical : Error {
      override val type: String = "error_during_execution"
    }

    /** It stopped for a reason this library does not know by name. */
    @JvmInline value class Unrecognized(override val type: String) : Error

    companion object {
      /**
       * Reads an error back from its textual form. A form this library does not know becomes
       * [Unrecognized] rather than a failure, so an engine that grows new outcomes stays readable.
       */
      fun parse(
          type: String,
      ): Error =
          when (type) {
            TurnLimitReached.type -> TurnLimitReached
            SpendBudgetReached.type -> SpendBudgetReached
            Critical.type -> Critical
            else -> Unrecognized(type = type)
          }
    }

    /** Textual form of this error. */
    val type: String
  }
}
