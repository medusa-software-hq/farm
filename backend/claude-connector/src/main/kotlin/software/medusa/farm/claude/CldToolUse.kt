package software.medusa.farm.claude

/**
 * A `tool_use` block reduced to what the connector reads off the wire: the tool [name] and the one
 * input field relevant to it (a file path, a shell command, or a search pattern). Classifying these
 * into semantic actions is the consumer's job — the wire adapter stays protocol-shaped, not
 * opinionated about meaning.
 */
data class CldToolUse(
    val name: String,
    val filePath: String?,
    val command: String?,
    val pattern: String?,
)
