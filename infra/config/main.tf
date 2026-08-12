terraform {
  required_version = ">= 1.14"
}

locals {
  # Shared across every environment; combined into per-environment derived values.
  domain  = "medusa.software"
  project = "farm"
  variant = "v1"

  # Hand-edited per-environment static constants (OAuth client ids, resource-name
  # suffix, GitHub Environment name). The single source of truth for values that
  # must stay identical between Terraform and the built artifacts (the API, the
  # worker runner, the CLI).
  environments = {
    prod = {
      gh_environment_name  = "production"
      resource_name_suffix = ""
      web_client_id        = "97246827152-5s18i6k7atkq7j1mm8j19s6tk443ednu.apps.googleusercontent.com"
      cli_client_id        = "97246827152-d2e73hif1ckri70a6osh139v6jmaesag.apps.googleusercontent.com"
    }
    staging = {
      gh_environment_name  = "staging"
      resource_name_suffix = "-staging"
      web_client_id        = "329509758995-8pqmbc01jp0lm3gilesi0g4ljcnh1cob.apps.googleusercontent.com"
      cli_client_id        = "329509758995-cn8lk8fcuen0u813a14m6cmfls3an24e.apps.googleusercontent.com"
    }
  }

  # The emitted contract: every environment's source fields plus the API host,
  # derived from project/variant/suffix/domain exactly as the deployed subdomain
  # is (see infra/common's api_host_name). Written to config.json, which the
  # non-Terraform consumers read.
  config = {
    for env, config in local.environments : env => merge(config, {
      api_host = "api.${local.project}-${local.variant}${config.resource_name_suffix}.${local.domain}"
    })
  }
}

output "config" {
  value = local.config
}
