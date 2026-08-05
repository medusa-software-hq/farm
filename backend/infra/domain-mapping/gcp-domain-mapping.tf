# Map the custom subdomain directly to the Cloud Run service (no load balancer).
# Google provisions and manages the TLS certificate for the mapped domain. Unlike
# the web app, the API service stays a public (`allUsers`) Cloud Run invoker —
# no IAP here, since auth is handled in the application layer
# (GoogleIdTokenAuthDecorator) for browser users.
#
# This root is applied by the shared org-level domain-mapper service account,
# which is a verified owner of the domain (a one-time org bootstrap performed in
# the `meta` repo) — so no per-project domain-ownership step is needed.
resource "google_cloud_run_domain_mapping" "api" {
  name     = local.counter_api_host_name
  location = module.common.gcp_primary_location
  project  = var.gcp_project_id

  metadata {
    namespace = var.gcp_project_id
  }

  spec {
    # The Cloud Run service itself is managed by the backend/infra foundation.
    route_name = module.common.gcp_api_run_service_name
  }
}

output "api_url" {
  description = "Public URL of the API — stable across redeploys, unlike Cloud Run's auto-generated URL."
  value       = "https://${local.counter_api_host_name}"
}
