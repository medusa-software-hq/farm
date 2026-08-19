package software.medusa.farm.claude

/**
 * Something a session produced while it was running.
 *
 * Events arrive in the order this library read them, which is close enough to the order the session
 * produced them to follow along with, but is not the session's own order: a [Warning] travels a
 * different pipe from a [Step] and can arrive ahead of one the session wrote first.
 */
sealed interface CldSessionEvent {
  /** A step the assistant took. */
  data class Step(
      val assistantStep: CldAssistantStep,
  ) : CldSessionEvent

  /**
   * Something the session said outside of what it says about its work. It carries on afterwards,
   * and a session that ends well may have said any number of these along the way.
   */
  data class Warning(
      val text: String,
  ) : CldSessionEvent
}
