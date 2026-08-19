package software.medusa.farm.claude

/**
 * Claude session identifier, assigned by the engine when the session starts.
 *
 * It names a session only for as long as the engine keeps it; nothing here treats it as a durable
 * handle that could be picked up again later.
 */
@JvmInline
value class CldSessionId(
    /** Textual session ID, in UUID form. */
    val id: String,
)
