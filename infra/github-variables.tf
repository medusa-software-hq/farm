# Actions variables consumed by CI/CD jobs.
#
# Environment-scoped: prod and staging each get their own project id, API URL,
# CI/CD identity and Artifact Registry. A workflow job's `environment:` is what
# makes its `vars.*` resolve to the right environment's values; each Terraform
# workspace writes only its own environment (the prod workspace fills
# `production`, the staging workspace fills `staging`).
#
# GCP_CICD_WI_PROVIDER_NAME, GCP_DOMAIN_MAPPER_SA_EMAIL and CLOUDFLARE_ZONE_ID
# are absent here on purpose: the Workload Identity pool, the shared
# domain-mapper SA and the Cloudflare zone are shared across environments, so
# both read the same repository-level values (set outside this root).

locals {
  # Everything the CI/CD jobs read, per environment.
  cicd_environment_variables = {
    API_URL                  = module.common.api_url
    GCP_PROJECT_ID           = google_project.gcp_project.project_id
    GCP_PRIMARY_LOCATION     = module.common.gcp_primary_location
    GCP_API_RUN_SERVICE_NAME = module.common.gcp_api_run_service_name
    GCP_WEB_RUN_SERVICE_NAME = module.common.gcp_web_run_service_name
    GCP_CICD_SA_EMAIL        = google_service_account.cicd_sa.email
    GCP_AR_REPO_HOSTNAME     = split("/", google_artifact_registry_repository.primary.registry_uri)[0]
    GCP_AR_REPO_ENDPOINT     = local.gcp_ar_repo_endpoint
    GOOGLE_CLIENT_ID         = module.common.google_web_client_id
    GOOGLE_ALLOWED_DOMAIN    = module.common.organization_domain
    # The releases app's client id — public; its private key is a separate secret.
    GH_RELEASES_CLIENT_ID = module.common.gh_releases_client_id
  }
}

resource "github_actions_environment_variable" "cicd" {
  for_each = local.cicd_environment_variables

  repository    = data.github_repository.this.name
  environment   = module.common.gh_environment_name
  variable_name = each.key
  value         = each.value
}
