locals {
  # Domain — defined in infra/common so the host this root publishes and the
  # API_URL that CI/CD bakes into the web build can never drift apart.
  counter_api_subdomain_name = module.common.api_subdomain_name
  counter_api_host_name      = module.common.api_host_name
}
