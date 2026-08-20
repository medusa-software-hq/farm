package software.medusa.farm.github

/** What an App may do with a permission it holds. */
enum class GhPermissionMode(val wireValue: String) {
  Read("read"),
  Write("write");

  /** Whether holding this is enough for something that asks for [required]. */
  fun covers(required: GhPermissionMode): Boolean = this == Write || required == Read
}
