package software.medusa.farm.shared

import kotlinx.serialization.Serializable

/**
 * A backend-neutral record of what a coding agent did in one run — its ordered [steps], and only
 * those. Modeled over "agent actions", not any single backend's wire protocol, so Claude maps into
 * it today and another agent backend maps into it tomorrow. Run metadata (outcome, cost) lives on
 * the [SessionRun] record, not here.
 */
@Serializable
data class AgentRunLog(
    val steps: List<AgentStep>,
)
