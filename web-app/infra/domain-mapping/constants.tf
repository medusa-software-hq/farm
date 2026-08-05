locals {
  # Domain — per environment via infra/common (prod keeps its pre-split
  # subdomain; staging gets its own for free).
  farm_web_subdomain_name = module.common.subdomain_label
  farm_web_host_name      = "${local.farm_web_subdomain_name}.${module.common.organization_domain}"
}
