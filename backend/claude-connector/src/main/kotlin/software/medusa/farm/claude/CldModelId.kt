package software.medusa.farm.claude

/** Claude model identifier. */
@JvmInline
value class CldModelId(
    /** Textual model ID, as the engine reports it, e.g. `claude-opus-5`. */
    val id: String,
) {
  /**
   * Whether [reported] satisfies this model identifier.
   *
   * An exact match always satisfies. A dated variant — the reported ID equal to this ID followed by
   * a hyphen and an eight-digit date — satisfies too, since the CLI may resolve a configured ID to
   * a timestamped variant of the same model (e.g. `claude-opus-4-5` resolves to
   * `claude-opus-4-5-20251101`). Any other suffix is a different model and does not satisfy.
   */
  fun matches(reported: CldModelId): Boolean =
      reported.id == id || reported.id.matches(Regex("${Regex.escape(id)}-\\d{8}"))

  /**
   * The models this library has been run against, by the full id the CLI reports for each.
   *
   * Full ids rather than the aliases the CLI also takes: an alias is resolved to an id before the
   * session reports one, so a session asked for an alias reports something [matches] refuses, and
   * would refuse every time. An id belongs here once something has run on it and seen it reported
   * back, rather than because it is expected to exist.
   */
  companion object {
    val Opus5 = CldModelId("claude-opus-5")

    val Opus45 = CldModelId("claude-opus-4-5")
  }
}
