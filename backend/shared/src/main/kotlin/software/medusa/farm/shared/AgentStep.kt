package software.medusa.farm.shared

import kotlinx.serialization.Serializable

/**
 * One step the agent took: its message/reasoning ([text], genuine prose) and the tool actions it
 * performed in that step. Either may be empty.
 */
@Serializable
data class AgentStep(
    val text: String,
    val toolActions: List<AgentToolAction>,
)
