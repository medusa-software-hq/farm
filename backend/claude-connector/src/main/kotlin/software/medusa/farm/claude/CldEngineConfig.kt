package software.medusa.farm.claude

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Static configuration for [CldProperAgent] — everything that varies per worker/deployment rather
 * than per run.
 *
 * @property environment the base subprocess environment, injected verbatim into the (replace-not-
 *   inherit) child: the auth vars for the active rung plus `PATH`. `HOME` is **not** set here — the
 *   agent overlays it per run so each session persists under a caller-controlled directory.
 * @property model optional `--model` override; `null` uses the CLI's default.
 * @property maxBudgetUsd the `--max-budget-usd` cap; a trip surfaces as an `error_max_budget_usd`
 *   result subtype. `null` omits the flag.
 * @property initTimeout how long to wait for the opening `init` handshake before a launch is
 *   considered failed — the run "starts" when claude speaks its protocol, not when the OS process
 *   does. Generous by default: it only guards a process that started but never answered.
 * @property toolPolicy the non-interactive permission posture.
 * @property appendSystemPrompt operating hints passed as `--append-system-prompt`, telling the
 *   otherwise-unframed agent that the message it receives **is** the task to solve, non-
 *   interactively, with no user to ask. Blank skips the flag.
 */
data class CldEngineConfig(
    val environment: Map<String, String>,
    val model: String?,
    val maxBudgetUsd: Double?,
    val initTimeout: Duration,
    val toolPolicy: CldToolPolicy,
    val appendSystemPrompt: String,
) {
  companion object {
    // A runaway guard, not a target: a real multi-file task legitimately spends a few dollars of
    // tool-calls, so a sub-dollar cap would guillotine genuine work mid-run.
    const val defaultMaxBudgetUsd = 10.00

    // The init banner is claude's opening line, emitted before any model work; 30s is far more than
    // it should ever take, so it only trips on a process that started but is not speaking.
    val defaultInitTimeout: Duration = 30.seconds

    const val defaultAppendSystemPrompt =
        "You are an autonomous coding agent running non-interactively. The single message you are " +
            "given is the text of a GitHub issue, and your job is to implement and solve it fully. " +
            "Do not wait for further instructions or scope confirmation, and never ask for " +
            "clarification; make reasonable assumptions and implement. Do not push commits or open " +
            "pull requests yourself."

    /**
     * Fills the deployment defaults around the one thing that is always caller-specific: the env.
     */
    fun default(environment: Map<String, String>): CldEngineConfig =
        CldEngineConfig(
            environment = environment,
            model = null,
            maxBudgetUsd = defaultMaxBudgetUsd,
            initTimeout = defaultInitTimeout,
            toolPolicy = CldToolPolicy.default(),
            appendSystemPrompt = defaultAppendSystemPrompt,
        )
  }
}
