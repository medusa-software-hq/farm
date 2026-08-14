package software.medusa.farm.worker

import software.medusa.farm.shared.AgentRunCost
import software.medusa.farm.shared.AgentRunLog
import software.medusa.farm.shared.AgentRunOutcome

/** The result of mapping a run's message stream: its action [log] plus the run's metadata. */
data class MappedAgentRun(
    val log: AgentRunLog,
    val outcome: AgentRunOutcome,
    val cost: AgentRunCost?,
)
