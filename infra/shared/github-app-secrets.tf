# The GitHub App keys the system tests run under, and the identity CI reads them as.
#
# In Secret Manager rather than as Actions secrets, and here rather than in an environment's
# project: they belong to CI itself, not to staging or production, which is the same reason the
# Temporal worker key lives in this project.
#
# What that buys over an Actions secret is a name Terraform owns and a failure that is loud. Adding
# a version to a secret that does not exist is an error; setting an Actions secret under a
# mistyped name quietly creates a second secret nobody reads.

# The Workload Identity pool is the organization's, not this repository's — the same state the
# per-environment CI/CD identities read it from.
data "terraform_remote_state" "meta_foundation" {
  backend = "gcs"

  config = {
    bucket = module.common.gcp_terraform_state_bucket_name
    prefix = "shared/foundation"
  }
}

locals {
  # `main` is the Farm App — the App Farm itself acts as, as against the apps that act on its
  # behalf. Every environment registers its own; the shared project holds more than one, so those
  # say which.
  gcp_cicd_wi_pool_name = data.terraform_remote_state.meta_foundation.outputs.gcp_cicd_wi_pool_name

  system_test_app_secret_ids = {
    farm    = "main-ephemeral-github-app-pem"
    fixture = "fixture-manager-github-app-pem"
  }
}

resource "google_secret_manager_secret" "system_test_app_keys" {
  for_each = local.system_test_app_secret_ids

  project   = local.shared_project_id
  secret_id = each.value

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis]
}

# The identity the system tests read those keys as.
#
# Deliberately not one of the deploy service accounts. Those are bound to the whole repository, so
# a pull request can already assume them, and they can write to a project and read every Terraform
# state; this one can read two secrets and do nothing else. It is bound the same way because a
# pull request is exactly where it is used.
resource "google_service_account" "system_tests_sa" {
  project      = local.shared_project_id
  account_id   = "github-actions-system-tests"
  display_name = "System tests CI service account"
  description  = "Reads the system tests' GitHub App keys. No other access, by design."

  depends_on = [google_project_service.apis]
}

resource "google_service_account_iam_member" "system_tests_sa_wi_user" {
  service_account_id = google_service_account.system_tests_sa.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${local.gcp_cicd_wi_pool_name}/attribute.repository/${module.common.gh_organization_name}/${module.common.gh_repo_name}"
}

resource "google_secret_manager_secret_iam_member" "system_tests_sa_accessor" {
  for_each = google_secret_manager_secret.system_test_app_keys

  project   = local.shared_project_id
  secret_id = each.value.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.system_tests_sa.email}"
}

# Repository-level, not environment-level: the system tests run on pull requests, where a job has
# no environment and `vars.*` would resolve to nothing.
#
# The project id carries a random suffix, so a workflow cannot sensibly spell it itself.
resource "github_actions_variable" "system_tests_ci" {
  for_each = {
    GCP_SYSTEM_TESTS_SA_EMAIL = google_service_account.system_tests_sa.email
    GCP_SHARED_PROJECT_ID     = local.shared_project_id
  }

  repository    = data.github_repository.this.name
  variable_name = each.key
  value         = each.value
}
