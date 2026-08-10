# Outputs consumed by other roots (e.g. infra/temporal reads shared_project_id via remote state) and
# by operators.

output "shared_project_id" {
  description = "GCP project ID of the shared cross-environment project."
  value       = local.shared_project_id
}

output "artifact_registry_repository" {
  description = "Shared Docker Artifact Registry repository endpoint."
  value       = google_artifact_registry_repository.shared.registry_uri
}
