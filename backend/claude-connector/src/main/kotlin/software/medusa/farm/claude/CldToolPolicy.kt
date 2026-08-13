package software.medusa.farm.claude

/**
 * The tool/permission policy, materialized as CLI flags. Non-interactive by construction: no prompt
 * may block a headless run.
 *
 * @property allowedTools passed as a single space-separated `--allowedTools` argument.
 * @property disallowedTools passed as a single space-separated `--disallowedTools` argument;
 *   git-push and GitHub tooling live here because publishing belongs to the caller, not the agent,
 *   and `AskUserQuestion` because there is no user to answer it.
 * @property permissionMode the `--permission-mode` value.
 * @property settingSources the `--setting-sources` value — `project` loads the target repo's
 *   `.claude/` + `CLAUDE.md` while excluding the host's `~/.claude`.
 */
data class CldToolPolicy(
    val allowedTools: List<String>,
    val disallowedTools: List<String>,
    val permissionMode: String,
    val settingSources: String,
) {
  companion object {
    fun default(): CldToolPolicy =
        CldToolPolicy(
            allowedTools = listOf("Read", "Edit", "Write", "Bash", "Glob", "Grep", "Task"),
            disallowedTools =
                listOf(
                    "Bash(git push:*)",
                    "Bash(gh:*)",
                    "WebFetch",
                    "WebSearch",
                    "AskUserQuestion",
                ),
            permissionMode = "acceptEdits",
            settingSources = "project",
        )
  }
}
