# The CLI's releases repo: holds the `ms-farm` fat-jar assets that the
# Homebrew formula downloads. Provisioned here (root infra) because it is
# infrastructure for the CLI, alongside the Actions variables the Publish CLI
# workflow reads. The Homebrew *tap* is shared org-wide (medusa-software-hq/
# homebrew-tap) and is NOT managed here — this project only pushes farm.rb to
# it via the releases GitHub App.
resource "github_repository" "farm_releases" {
  # Account-level and shared across environments (one releases bucket for the
  # CLI, per the flavor-constant note above), so it is owned by the prod
  # (default) workspace only — the staging workspace must not try to re-create
  # the same repo.
  count = terraform.workspace == "default" ? 1 : 0

  name        = module.common.gh_releases_repo_name
  description = "Release assets for the ${module.common.gh_repo_name} CLI."
  visibility  = "public"

  has_issues   = false
  has_projects = false
  has_wiki     = false

  # Seed an initial commit (a README on the default branch) at creation, so the
  # repo comes up with >=1 commit and a branch. The Publish CLI workflow's
  # `gh release create` needs a target commitish; an empty repo has none. This
  # is why the repo is created fresh here rather than hand-made and imported.
  auto_init = true
}
