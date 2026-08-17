package software.medusa.farm.claude

/**
 * One step of a run as it streams: the agent's message [text] plus the structured [toolUses] it
 * invoked in that step. Either may be empty. The `init` and `result` framing are not steps — they
 * seed [CldRunResult]; the consumer classifies the tool uses into semantic actions.
 */
data class CldStep(
    val text: String,
    val toolUses: List<CldToolUse>,
)
