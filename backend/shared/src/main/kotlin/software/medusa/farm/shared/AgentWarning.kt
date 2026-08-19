package software.medusa.farm.shared

import kotlinx.serialization.Serializable

/**
 * Something the agent's backend said outside of its account of the work — a notice, a complaint, a
 * deprecation. A run full of these can still be a run that went well.
 */
@Serializable
data class AgentWarning(
    val text: String,
) : AgentRunEntry
