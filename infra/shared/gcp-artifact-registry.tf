# Artifact Registry — a Docker repository for future cross-environment shared images. Provisioned
# here; no pipeline is wired to it yet.

resource "google_artifact_registry_repository" "shared" {
  project       = google_project.shared.project_id
  location      = module.common.gcp_primary_location
  repository_id = "${module.common.project_base_name}-shared"
  format        = "DOCKER"
  description   = "Shared cross-environment artifact repository."

  depends_on = [google_project_service.apis["artifactregistry.googleapis.com"]]
}
