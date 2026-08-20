# The label that opts an issue into Farm's pickup: the sweep starts work for issues carrying it and
# lists the rest without touching them. Declared here because it is Farm's input contract with
# whoever files the issue, and a repository without it is one Farm can never take work from.
resource "github_issue_label" "farm_ready" {
  repository  = github_repository.this.name
  name        = "farm:ready"
  color       = "5319e7"
  description = "Opt an issue into Farm's pickup queue."
}
