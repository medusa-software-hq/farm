# Wire these into the worker / temporal-demo (env vars). The API key is sensitive — fetch it with
# `terraform output -raw worker_api_key` and store it as a secret, never in plaintext config.

output "namespace_id" {
  description = "Full namespace id (name.account) — use as TEMPORAL_NAMESPACE."
  value       = temporalcloud_namespace.farm.id
}

output "grpc_address" {
  description = "gRPC endpoint — use as TEMPORAL_ADDRESS."
  value       = temporalcloud_namespace.farm.endpoints.grpc_address
}

output "worker_api_key" {
  description = "farm-worker service-account API key — use as TEMPORAL_API_KEY."
  value       = temporalcloud_apikey.farm_worker.token
  sensitive   = true
}

# Pass-through of the shared project holding worker-temporal-api-key, so the code generator can bake
# it (TEMPORAL_KEY_PROJECT) — the runner reads the key from this project regardless of its target env.
output "shared_project_id" {
  description = "GCP project holding the worker-temporal-api-key secret (the cross-environment shared project)."
  value       = local.shared_project_id
}
