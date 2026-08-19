package software.medusa.farm.claude

/** A single step taken by the assistant: what it said, and what it did. */
data class CldAssistantStep(
    /** Text the assistant produced in this step; empty if it only used tools. */
    val text: String,

    /** Tools the assistant used in this step; empty if it only spoke. */
    val toolUses: List<CldToolUse>,
)
