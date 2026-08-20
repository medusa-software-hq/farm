package software.medusa.farm.claude

import java.nio.file.Path

/** Everything that shapes one Claude session, beyond the prompt driving it. */
data class CldSessionConfig(
    /** Directory the assistant works in, and which it is expected to change in place. */
    val workspacePath: Path,

    /**
     * Directory the assistant keeps its own state in — settings, and the transcript of this
     * session. Give each session its own to keep sessions from seeing each other; give them a
     * shared one to pick up the state the CLI and the desktop app keep.
     */
    val configDirPath: Path,

    /**
     * The model the session is asked to run on. Passed as `--model` to the CLI; the session is
     * refused if the CLI reports a different model at startup.
     */
    val model: CldModelId,

    /** How much the assistant may do without being granted permission first. */
    val permissionMode: CldPermissionMode,

    /** Which stored settings the session is run under. Empty leaves the choice to the engine. */
    val settingSources: List<CldSettingSource>,

    /** Tools the assistant may use. Empty leaves the choice to the engine. */
    val allowedToolRules: List<CldToolRule>,

    /** Tools the assistant may not use, whatever [allowedToolRules] says. */
    val disallowedToolRules: List<CldToolRule>,

    /**
     * Standing instructions added to the ones the assistant already has, for as long as the session
     * lasts. Blank adds none.
     */
    val systemPromptSuffix: String,

    /**
     * Most the session may spend before it is stopped. A guard against a session that runs away,
     * not a target — a trip ends the session as [CldRunStatus.Error.SpendBudgetReached].
     */
    val spendBudget: CldCost,
)
