package software.medusa.farm.shared

import kotlinx.serialization.Serializable

/**
 * One thing that happened during an agent run, in the order it happened. A run reads as a single
 * ordered account rather than as separate lists, so whatever is shown of it — live or afterwards —
 * can put each entry where it belongs among the others.
 */
@Serializable sealed interface AgentRunEntry
