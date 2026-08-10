# GCP organization data

data "google_organization" "gcp_organization" {
  domain = module.common.organization_domain
}

data "google_billing_account" "gcp_billing_account" {
  display_name = "My Billing Account"
  open         = true
}

# GCP project — one shared project across all environments (prod + staging), holding cross-environment
# resources such as the Temporal worker key and future shared images.

resource "random_id" "gcp_project_random_id" {
  byte_length = 4
}

resource "google_project" "shared" {
  org_id          = data.google_organization.gcp_organization.org_id
  billing_account = data.google_billing_account.gcp_billing_account.id

  name       = "${module.common.project_base_name} - shared"
  project_id = "${module.common.gcp_organization_prefix}-${module.common.project_base_name}-shared-${random_id.gcp_project_random_id.hex}"

  auto_create_network = false
}

locals {
  shared_project_id = google_project.shared.project_id
}

# Enabled GCP APIs
resource "google_project_service" "apis" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "secretmanager.googleapis.com",
    "serviceusage.googleapis.com",
    "storage.googleapis.com",
  ])

  project            = google_project.shared.id
  service            = each.key
  disable_on_destroy = false
}
