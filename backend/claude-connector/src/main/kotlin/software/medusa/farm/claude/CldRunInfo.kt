package software.medusa.farm.claude

/**
 * What claude reports in its opening `init` handshake — available on the [CldRun] the moment it is
 * launched, not at the end of the run. [sessionId] is the resume anchor; [model] and [tools] are
 * the model the CLI resolved and the tools it enabled.
 */
data class CldRunInfo(
    val sessionId: String,
    val model: String?,
    val tools: List<String>,
)
