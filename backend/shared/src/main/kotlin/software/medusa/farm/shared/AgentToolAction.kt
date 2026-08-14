package software.medusa.farm.shared

import kotlinx.serialization.Serializable

/**
 * A single tool action an agent took, classified into the closed set that matters for showing and
 * reasoning about a run. Backend-neutral: a mapper turns each backend's tool vocabulary into these.
 * [Other] is the deliberate escape hatch for the open tail (subagents, web tools, MCP servers, …).
 */
@Serializable
sealed interface AgentToolAction {
  @Serializable data class EditFile(val path: String) : AgentToolAction

  @Serializable data class ReadFile(val path: String) : AgentToolAction

  @Serializable data class RunCommand(val command: String) : AgentToolAction

  @Serializable data class Search(val query: String) : AgentToolAction

  @Serializable data class Other(val tool: String) : AgentToolAction
}
