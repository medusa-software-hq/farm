# The minted worker key, published to GCP Secret Manager so consumers read it uniformly (the worker
# via TEMPORAL_API_KEY, the runner via this same secret). One namespace is shared across environments
# for now, so the key lives in the prod project (var.gcp_project_id) — see README.
resource "google_secret_manager_secret" "worker_temporal_api_key" {
  secret_id = "worker-temporal-api-key"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "worker_temporal_api_key" {
  secret      = google_secret_manager_secret.worker_temporal_api_key.id
  secret_data = temporalcloud_apikey.farm_worker.token
}
