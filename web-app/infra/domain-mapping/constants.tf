locals {
  # Domain — per environment via infra/common (prod keeps its pre-split
  # subdomain; staging gets its own for free).
  counter_web_subdomain_name = module.common.subdomain_label
  counter_web_host_name      = "${local.counter_web_subdomain_name}.${module.common.organization_domain}"
}
