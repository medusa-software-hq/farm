terraform {
  required_version = ">= 1.14"
}

locals {
  # Deployment environment, derived from the Terraform workspace. The `default`
  # workspace is production — its state predates the prod/staging split, so it
  # stays in place (no state migration); every other workspace is a named
  # non-prod environment. This is the single dimension that distinguishes prod
  # from staging across every root that imports this module.
  environment = terraform.workspace == "default" ? "prod" : terraform.workspace

  # Per-environment values. Everything *outside* this map is shared across
  # environments (same GCP meta project, state bucket, region, org, repo, …);
  # only what genuinely differs per environment lives here. `default`/prod
  # resolves to exactly the values used before this split, so introducing the
  # workspace dimension is a no-op on the prod state.
  environment_config = {
    prod = {
      # The GitHub deployment Environment holding this environment's Actions
      # variables (see .github/config). Note prod's is "production", not "prod".
      gh_environment_name = "production"

      # Suffix appended to the GCP project's *display name*. Empty for prod: its
      # project predates the split and must not be renamed.
      gcp_project_name_suffix = ""

      # DNS/Neon-safe suffix appended to derived resource names (web/API
      # subdomain, Neon project). Empty for prod so its subdomain and Neon
      # project keep their pre-split names.
      resource_name_suffix = ""
    }
    staging = {
      gh_environment_name     = "staging"
      gcp_project_name_suffix = " - staging"
      resource_name_suffix    = "-staging"
    }
  }
  selected_environment = local.environment_config[local.environment]

  # Per-environment OAuth client ids come from the resolved cache in
  # infra/environments — the one place they're defined, shared with the CLI so
  # the two can't drift (its guard aborts the plan on a stale cache). `path.module`
  # keeps this relative to infra/common regardless of which root imports it.
  environments_cache  = jsondecode(file("${path.module}/../environments/environments.cache.json"))
  selected_env_config = local.environments_cache[local.environment]

  organization_domain = "medusa.software"

  gcp_organization_prefix         = "ms"
  gcp_primary_location            = "europe-west1"
  gcp_meta_project_id             = "ms-meta-9aaf29f0"
  gcp_terraform_state_bucket_name = "ms-tfstate-c1984596bdabf023"

  gcp_api_run_service_name = "api"
  gcp_web_run_service_name = "web"

  # The GitHub org + repo that holds the code and runs CI/CD — the SAME for
  # every environment (one repo, one Actions pipeline), so a flavor constant,
  # NOT part of environment_config. Used for WIF principalSets, the `github`
  # provider owner, and Terraform state prefixes.
  gh_organization_name   = "medusa-software-hq"
  gh_repo_name           = "farm"
  gh_api_url_var_name    = "API_URL"
  gh_default_branch_name = "trunk/v1"

  # The releases repo the CLI publishes its fat jar to (provisioned by the root
  # infra), and the GitHub App the Publish CLI workflow authenticates as to push
  # releases + the Homebrew formula. Flavor constants — one app, one releases
  # repo, shared across environments.
  gh_releases_repo_name = "farm-releases"
  # 🎨 TEMPLATE POST-EJECT: Create a GitHub App and change its client id here 👇
  gh_releases_client_id = "Iv23liqPdxH2VoWlrH7c" # "Medusa Farm Releaser"

  project_base_name = "farm"
  project_variant   = "v1"

  gh_environment_name     = local.selected_environment.gh_environment_name
  gcp_project_name_suffix = local.selected_environment.gcp_project_name_suffix
  resource_name_suffix    = local.selected_environment.resource_name_suffix

  # Subdomain under organization_domain — per environment (the suffix is empty
  # for prod). The web app is published at `<subdomain_label>.<domain>` and the
  # API at `api.<subdomain_label>.<domain>`; staging gets its own subdomain for
  # free.
  subdomain_label = "${local.project_base_name}-${local.project_variant}${local.resource_name_suffix}"

  # The API's public host, defined once: the domain mapping publishes it (DNS
  # record + Cloud Run mapping) and CI/CD hands it to the web build as
  # VITE_API_URL. Two definitions of the same string would silently drift the
  # moment the subdomain changed.
  api_subdomain_name = "api.${local.subdomain_label}"
  api_host_name      = "${local.api_subdomain_name}.${local.organization_domain}"
  api_url            = "https://${local.api_host_name}"

  # Google OAuth 2.0 client ID — per environment (from the shared cache). It is
  # the audience of the user tokens that environment's API accepts, and the
  # client its SPA signs in with.
  google_web_client_id = local.selected_env_config.web_client_id

  # Google OAuth 2.0 Desktop client ID — per environment; the CLI's audience and
  # the client it signs in with (loopback + PKCE).
  google_cli_client_id = local.selected_env_config.cli_client_id
}

output "organization_domain" {
  value = local.organization_domain
}

output "gcp_organization_prefix" {
  value = local.gcp_organization_prefix
}

output "gcp_primary_location" {
  value = local.gcp_primary_location
}

output "gcp_meta_project_id" {
  value = local.gcp_meta_project_id
}

output "gcp_terraform_state_bucket_name" {
  value = local.gcp_terraform_state_bucket_name
}

output "gcp_api_run_service_name" {
  value = local.gcp_api_run_service_name
}

output "gcp_web_run_service_name" {
  value = local.gcp_web_run_service_name
}

output "gh_organization_name" {
  value = local.gh_organization_name
}

output "gh_repo_name" {
  value = local.gh_repo_name
}

output "gh_api_url_var_name" {
  value = local.gh_api_url_var_name
}

output "project_base_name" {
  value = local.project_base_name
}

output "project_variant" {
  value = local.project_variant
}

output "subdomain_label" {
  value = local.subdomain_label
}

output "api_subdomain_name" {
  value = local.api_subdomain_name
}

output "api_host_name" {
  value = local.api_host_name
}

output "api_url" {
  value = local.api_url
}

output "google_web_client_id" {
  value = local.google_web_client_id
}

output "google_cli_client_id" {
  value = local.google_cli_client_id
}

output "gh_releases_repo_name" {
  value = local.gh_releases_repo_name
}

output "gh_releases_client_id" {
  value = local.gh_releases_client_id
}

output "environment" {
  value = local.environment
}

output "gh_environment_name" {
  value = local.gh_environment_name
}

output "gh_default_branch_name" {
  value = local.gh_default_branch_name
}

output "gcp_project_name_suffix" {
  value = local.gcp_project_name_suffix
}

output "resource_name_suffix" {
  value = local.resource_name_suffix
}
