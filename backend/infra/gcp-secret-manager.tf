# Secret holding the Neon JDBC connection string, injected into Cloud Run.
resource "google_secret_manager_secret" "database_url" {
  project   = var.gcp_project_id
  secret_id = "${module.common.gcp_api_run_service_name}-database-url"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "database_url" {
  secret      = google_secret_manager_secret.database_url.id
  secret_data = local.database_jdbc_url
}

# Allow the Cloud Run service account to read the connection-string secret.
resource "google_secret_manager_secret_iam_member" "primary_service_sa_database_url_accessor" {
  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.database_url.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.primary_service_sa.email}"
}

# Secret holding the GitHub App private key (PKCS#8 PEM), injected into Cloud Run.
resource "google_secret_manager_secret" "github_app_pem" {
  project   = var.gcp_project_id
  secret_id = "${module.common.gcp_api_run_service_name}-github-app-pem"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "github_app_pem" {
  secret      = google_secret_manager_secret.github_app_pem.id
  secret_data = var.github_app_pem
}

# Allow the Cloud Run service account to read the GitHub App PEM secret.
resource "google_secret_manager_secret_iam_member" "primary_service_sa_github_app_pem_accessor" {
  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.github_app_pem.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.primary_service_sa.email}"
}

# The grant letting this env's SA read the shared worker-temporal-api-key does NOT live here: that
# secret is in the cross-environment shared project, where the env CI/CD SA has no IAM-admin rights.
# It is granted from infra/temporal (operator-applied, owns the secret), which reads this env's api-SA
# email from the primary_service_sa_email output in gcp-service.tf.
