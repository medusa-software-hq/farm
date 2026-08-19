package software.medusa.farm.claude

/**
 * What the engine passes on of the machine it runs on. Named rather than inherited: the assistant
 * runs commands and can read its own environment, and what it reads reaches a transcript that is
 * kept and passed on, so everything it sees is here by decision.
 */
data class CldSystemEnvMap(
    /** Where the assistant looks for the tools it runs. */
    val path: String,

    /** Home directory the commands the assistant runs read their own settings from. */
    val home: String,
)
