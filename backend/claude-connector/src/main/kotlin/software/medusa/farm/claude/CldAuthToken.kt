package software.medusa.farm.claude

/** The credential the engine authenticates with. */
@JvmInline
value class CldAuthToken(
    val token: String,
)
