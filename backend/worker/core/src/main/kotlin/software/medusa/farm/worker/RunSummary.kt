package software.medusa.farm.worker

/** A distillation of what a coding-agent run did. */
@JvmInline
value class RunSummary(
    /** The summary itself. Never blank — a backend that says nothing raises instead. */
    val text: String,
)
