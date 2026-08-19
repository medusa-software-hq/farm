package software.medusa.farm.claude

/** A single use of a tool by the assistant. */
data class CldToolUse(
    /** Name of the tool used. */
    val name: String,

    /** Path of the file the tool was pointed at, if it takes one. */
    val filePath: String?,

    /** Shell command the tool was asked to run, if it takes one. */
    val command: String?,

    /** Search pattern the tool was given, if it takes one. */
    val pattern: String?,
)
