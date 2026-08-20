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
}
