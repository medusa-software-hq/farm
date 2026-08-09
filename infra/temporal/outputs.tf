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
