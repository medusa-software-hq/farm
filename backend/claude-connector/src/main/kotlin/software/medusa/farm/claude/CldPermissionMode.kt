package software.medusa.farm.claude

/** How much the assistant may do in a session without being granted permission first. */
sealed interface CldPermissionMode {
  /** Every action that needs permission has to be granted before it happens. */
  data object Default : CldPermissionMode {
    override val value: String = "default"
  }

  /** Changes to files are taken as granted; anything else still has to be. */
  data object AcceptEdits : CldPermissionMode {
    override val value: String = "acceptEdits"
  }

  /** The assistant may only work out what it would do, never do it. */
  data object Plan : CldPermissionMode {
    override val value: String = "plan"
  }

  /** Permission is never asked for; whatever would need it does not happen. */
  data object DontAsk : CldPermissionMode {
    override val value: String = "dontAsk"
  }

  /** Everything is taken as granted. Only sound where the workspace is expendable. */
  data object BypassPermissions : CldPermissionMode {
    override val value: String = "bypassPermissions"
  }

  /** Textual form of this mode. */
  val value: String
}
