terraform {
  required_version = ">= 1.14"
}

locals {
  # Shared across every environment; combined into per-environment derived values.
  domain  = "medusa.software"
  project = "farm"
  variant = "v1"

  # Shared Temporal Cloud coordinates (non-secret): the same namespace serves
  # every environment. The API and the worker runner bake these; the API key
  # itself is a secret fetched at runtime, never here.
  temporal = {
    address     = "us-east-1.aws.api.temporal.io:7233"
    namespace   = "farm.kr9zt"
    key_project = "ms-farm-shared-c83577a8"
  }

  # Hand-edited per-environment static constants (OAuth + GitHub App client ids,
  # resource-name suffix, GitHub Environment name). The single source of truth
  # for values that must stay identical between Terraform and the built artifacts
  # (the API, the worker runner, the CLI).
  environments = {
    prod = {
      gh_environment_name  = "production"
      resource_name_suffix = ""
      web_client_id        = "97246827152-5s18i6k7atkq7j1mm8j19s6tk443ednu.apps.googleusercontent.com"
      cli_client_id        = "97246827152-d2e73hif1ckri70a6osh139v6jmaesag.apps.googleusercontent.com"
      github_app_client_id = "Iv23liqulmJ6FLb48pE5"
    }
    staging = {
      gh_environment_name  = "staging"
      resource_name_suffix = "-staging"
      web_client_id        = "329509758995-8pqmbc01jp0lm3gilesi0g4ljcnh1cob.apps.googleusercontent.com"
      cli_client_id        = "329509758995-cn8lk8fcuen0u813a14m6cmfls3an24e.apps.googleusercontent.com"
      github_app_client_id = "Iv23liUa4I1Mh1CZwWaH"
    }
  }

  # The emitted contract written to config.json, which the non-Terraform
  # consumers read. Per environment: every source field plus the API host,
  # derived from project/variant/suffix/domain exactly as the deployed subdomain
  # is (see infra/common's api_host_name).
  config = {
    project  = local.project
    variant  = local.variant
    domain   = local.domain
    temporal = local.temporal
    environments = {
      for env, config in local.environments : env => merge(config, {
        api_host = "api.${local.project}-${local.variant}${config.resource_name_suffix}.${local.domain}"
      })
    }
  }
}

output "config" {
  value = local.config
}
