# Dedicated service account for the Cloud Run service.
resource "google_service_account" "primary_service_sa" {
  project      = var.gcp_project_id
  account_id   = "${module.common.gcp_api_run_service_name}-sa"
  display_name = "Cloud Run Service Account"
}

# The primary Cloud Run service for the app
resource "google_cloud_run_v2_service" "primary" {
  project             = var.gcp_project_id
  name                = module.common.gcp_api_run_service_name
  location            = module.common.gcp_primary_location
  deletion_protection = false # This project is experimental
  ingress             = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.primary_service_sa.email

    # Scale to zero is deliberate: this is an experimental, low-traffic app, so we don't pay for an
    # always-on instance. The trade-off is a JVM cold start on the first request after idle (~13s of
    # boot, which the browser's CORS preflight absorbs before any data loads). We attack that boot
    # cost directly — startup_cpu_boost below, async client init, the loading UI — rather than by
    # keeping an instance warm.
    scaling {
      min_instance_count = 0
    }

    containers {
      # Initial placeholder; CI/CD will deploy the real image from Artifact Registry.
      image = "us-docker.pkg.dev/cloudrun/container/hello"

      # Full CPU during startup so the cold start boots quickly rather than crawling on throttled
      # CPU (the boot is CPU-bound classload + client init). Costs nothing at steady state.
      resources {
        startup_cpu_boost = true
      }

      ports {
        container_port = 8080
      }

      env {
        name  = "GOOGLE_WEB_CLIENT_ID"
        value = module.common.google_web_client_id
      }

      # Also accept ID tokens minted by the CLI's Desktop OAuth client, so
      # `ms-farm` can call the API (see GoogleIdTokenAuthDecorator).
      env {
        name  = "GOOGLE_CLI_CLIENT_ID"
        value = module.common.google_cli_client_id
      }

      env {
        name  = "GOOGLE_ALLOWED_DOMAIN"
        value = module.common.organization_domain
      }

      env {
        name  = "CORS_ALLOWED_ORIGIN_REGEX"
        value = "https://[a-z0-9-]+\\.medusa\\.software"
      }

      env {
        name = "DATABASE_URL"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database_url.secret_id
            version = "latest"
          }
        }
      }

      env {
        name  = "GITHUB_APP_CLIENT_ID"
        value = module.common.github_app_client_id
      }

      env {
        name = "GITHUB_APP_PEM"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.github_app_pem.secret_id
            version = "latest"
          }
        }
      }

      # The Temporal Cloud key the API uses to start repo-sync workflows on org link. It lives in the
      # cross-environment shared project, so it is referenced by its fully-qualified secret name.
      # Required: a missing key fails the API at startup rather than silently disabling sync.
      env {
        name = "TEMPORAL_API_KEY"
        value_source {
          secret_key_ref {
            secret  = "projects/${data.terraform_remote_state.shared.outputs.shared_project_id}/secrets/${module.common.secret_ids.temporal_api_key}"
            version = "latest"
          }
        }
      }
    }
  }

  depends_on = [
    google_secret_manager_secret_version.database_url,
    google_secret_manager_secret_version.github_app_pem,
  ]

  # The image is managed by CI/CD after initial creation.
  # Env vars are managed by Terraform and must not be overwritten by deploys.
  lifecycle {
    # noinspection HILUnresolvedReference
    ignore_changes = [
      template[0].containers[0].image,
      client,
      client_version,
    ]
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }
}

# Allow unauthenticated (public) access — no auth for now.
resource "google_cloud_run_v2_service_iam_member" "public_invoker" {
  project  = google_cloud_run_v2_service.primary.project
  location = google_cloud_run_v2_service.primary.location
  name     = google_cloud_run_v2_service.primary.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

output "cloud_run_primary_service_url" {
  value = google_cloud_run_v2_service.primary.uri
}

# Consumed by infra/temporal (operator-applied) to grant this env's SA read access to the shared
# worker-temporal-api-key secret. backend/infra applies first so this output exists before that grant.
output "primary_service_sa_email" {
  value = google_service_account.primary_service_sa.email
}
