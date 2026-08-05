locals {
  # Domain — defined in infra/common so the host this root publishes and the
  # API_URL that CI/CD bakes into the web build can never drift apart.
  farm_api_subdomain_name = module.common.api_subdomain_name
  farm_api_host_name      = module.common.api_host_name
}
