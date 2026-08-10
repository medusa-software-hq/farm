# The Farm namespace, authenticated with API keys (not mTLS).
resource "temporalcloud_namespace" "farm" {
  name           = "farm"
  regions        = var.regions
  api_key_auth   = true
  retention_days = 14
}

# A namespace-scoped service account for Farm's worker (and the temporal-demo proof). Write access is
# enough to run workers and start workflows; it can't touch the account or other namespaces.
resource "temporalcloud_service_account" "farm_worker" {
  name        = "farm-worker"
  description = "Farm worker + temporal-demo; write access to the farm namespace."

  namespace_scoped_access = {
    namespace_id = temporalcloud_namespace.farm.id
    permission   = "write"
  }
}

# The worker's API key. Expiry is set to the trial window as a forcing function to rotate; move it
# out before then. `terraform output -raw worker_api_key` prints the secret to stash as a secret.
resource "temporalcloud_apikey" "farm_worker" {
  display_name = "farm-worker"
  owner_type   = "service-account"
  owner_id     = temporalcloud_service_account.farm_worker.id
  expiry_time  = "2026-11-07T00:00:00Z"
  disabled     = false
}
