terraform {
  required_version = ">= 1.14"
}

locals {
  # Hand-edited per-environment static constants (OAuth client ids, resource-name
  # suffix, GitHub Environment name) plus the shared project/variant/domain they
  # combine into derived values. The single source of truth for values that must
  # stay identical between Terraform and the CLI.
  input = jsondecode(file("${path.module}/environments.input.json"))

  # The fully resolved per-environment bundle: every input field plus the API
  # host, derived from project/variant/suffix/domain exactly as the deployed
  # subdomain is (see infra/common's api_host_name).
  derived = {
    for env, config in local.input.environments : env => merge(config, {
      api_host = "api.${local.input.project}-${local.input.variant}${config.resource_name_suffix}.${local.input.domain}"
    })
  }

  # The committed copy of `derived` that non-Terraform consumers read. Kept
  # honest by the guard below.
  cache = jsondecode(file("${path.module}/environments.cache.json"))
}

# The guard: two representations of the same bundle must agree, or the plan
# aborts before any consumer can read a stale cache. The precondition runs at
# plan on pure locals + this builtin resource, so CI enforces it without cloud
# credentials.
resource "terraform_data" "cache_guard" {
  input = local.derived

  lifecycle {
    precondition {
      condition     = local.derived == local.cache
      error_message = "environments.cache.json is stale — regenerate it with `task environments:regenerate`."
    }
  }
}

output "derived" {
  value = local.derived
}
