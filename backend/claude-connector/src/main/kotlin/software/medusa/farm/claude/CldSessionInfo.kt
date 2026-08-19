package software.medusa.farm.claude

/** Basic info about a Claude session. */
data class CldSessionInfo(
    /** Session identifier. */
    val sessionId: CldSessionId,

    /** Model driving the session. */
    val modelId: CldModelId,

    /** Specifiers of the tools available in the session. */
    val availableToolSpecifiers: Set<CldToolRule>,
)
