# The minted worker key, published to GCP Secret Manager so consumers read it uniformly (the worker
# via TEMPORAL_API_KEY, the runner via this same secret). One namespace is shared across environments,
# so the key lives in the cross-environment shared project (infra/shared) rather than a per-env one.
resource "google_secret_manager_secret" "worker_temporal_api_key" {
  project   = local.shared_project_id
  secret_id = "worker-temporal-api-key"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "worker_temporal_api_key" {
  secret      = google_secret_manager_secret.worker_temporal_api_key.id
  secret_data = temporalcloud_apikey.farm_worker.token
}

# Both environments' API service accounts read the one shared key. The runner and worker in each
# environment authenticate as that environment's api-sa, so both need secretAccessor on this secret.
resource "google_secret_manager_secret_iam_member" "api_sa_accessors" {
  for_each = toset([
    "serviceAccount:api-sa@ms-farm-11efee2b.iam.gserviceaccount.com", # prod
    "serviceAccount:api-sa@ms-farm-98981146.iam.gserviceaccount.com", # staging
  ])

  project   = local.shared_project_id
  secret_id = google_secret_manager_secret.worker_temporal_api_key.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = each.value
}
