package software.medusa.farm.claude

/** A body of stored settings a session can be run under. */
sealed interface CldSettingSource {
  /** The settings kept alongside the code in the workspace. */
  data object Project : CldSettingSource {
    override val value: String = "project"
  }

  /** The settings belonging to whoever owns the machine. */
  data object User : CldSettingSource {
    override val value: String = "user"
  }

  /** The settings kept privately in the workspace, outside the code. */
  data object Local : CldSettingSource {
    override val value: String = "local"
  }

  /** Textual form of this source. */
  val value: String
}
