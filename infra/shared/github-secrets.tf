# Actions secrets consumed by CI/CD jobs. Repo-scoped — one per account, not one
# per environment — which is why they live in this non-workspaced root.
#
# Unlike the CI/CD variables, a secret's value is one Terraform cannot know, so
# it is set out of band and this only pins the name: declaring the resource is
# what keeps the workflow's `secrets.*` reference from silently resolving to
# nothing (an unprovisioned or mistyped secret name is otherwise an invisible
# CI failure — here, the integration tests would quietly skip forever).

# The claude CLI auth token the claude-connector integration suite runs under.
resource "github_actions_secret" "claude_code_oauth_token" {
  repository  = data.github_repository.this.name
  secret_name = "CLAUDE_CODE_OAUTH_TOKEN"

  # Placeholder: the real token (from `claude setup-token`) is set out of band,
  # and Terraform ignores the value from then on.
  plaintext_value = "set-out-of-band"

  lifecycle {
    ignore_changes = [plaintext_value]
  }
}

# The OpenRouter key the run-summary integration suite runs under.
resource "github_actions_secret" "openrouter_api_key" {
  repository  = data.github_repository.this.name
  secret_name = "OPENROUTER_API_KEY"

  # Placeholder: the real key (from the OpenRouter dashboard) is set out of
  # band, and Terraform ignores the value from then on.
  plaintext_value = "set-out-of-band"

  lifecycle {
    ignore_changes = [plaintext_value]
  }
}

# The private key of the GitHub App the ephemeral farm drives its test org with.
# The App's client id is not secret and is set inline in the workflow; only this is.
resource "github_actions_secret" "farm_ephemeral_github_app_pem" {
  repository  = data.github_repository.this.name
  secret_name = "FARM_EPHEMERAL_GITHUB_APP_PEM"

  # Placeholder: the real PEM (generated on the App's settings page) is set out
  # of band, and Terraform ignores the value from then on. This has to exist
  # before the real value is set — creating it afterwards would PUT the
  # placeholder over it, which ignore_changes does not prevent on create.
  plaintext_value = "set-out-of-band"

  lifecycle {
    ignore_changes = [plaintext_value]
  }
}

# The private key of the GitHub App that makes and drops each ephemeral run's repository. Separate
# from the farm App on purpose: creating and deleting repositories is the harness's job, not Farm's,
# and Farm's rights in a throwaway environment should be the shape they are in a real one.
resource "github_actions_secret" "fixture_manager_github_app_pem" {
  repository  = data.github_repository.this.name
  secret_name = "FIXTURE_MANAGER_GITHUB_APP_PEM"

  # Placeholder: the real PEM is set out of band, and Terraform ignores the value from then on. As
  # above, this has to exist before the real value does.
  plaintext_value = "set-out-of-band"

  lifecycle {
    ignore_changes = [plaintext_value]
  }
}

