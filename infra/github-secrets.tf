# Actions secrets consumed by CI/CD jobs.
#
# Unlike the CI/CD variables, a secret's value is one Terraform cannot know, so
# it is set out of band and this only pins the name: declaring the resource is
# what keeps the workflow's `secrets.*` reference from silently resolving to
# nothing (an unprovisioned or mistyped secret name is otherwise an invisible
# CI failure — here, the integration tests would quietly skip forever).

# The claude CLI auth token the claude-connector integration suite runs under.
# Repo-scoped, so — like the releases repo — it is owned by the prod (default)
# workspace alone; the staging workspace must not fight it over the same secret.
resource "github_actions_secret" "claude_code_oauth_token" {
  count = terraform.workspace == "default" ? 1 : 0

  repository  = data.github_repository.this.name
  secret_name = "CLAUDE_CODE_OAUTH_TOKEN"

  # Placeholder: the real token (from `claude setup-token`) is set out of band,
  # and Terraform ignores the value from then on.
  plaintext_value = "set-out-of-band"

  lifecycle {
    ignore_changes = [plaintext_value]
  }
}
