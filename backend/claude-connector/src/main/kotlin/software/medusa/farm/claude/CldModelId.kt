package software.medusa.farm.claude

/** Claude model identifier. */
@JvmInline
value class CldModelId(
    /** Textual model ID, as the engine reports it, e.g. `claude-opus-5`. */
    val id: String,
)
