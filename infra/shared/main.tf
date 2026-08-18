# Configuration

terraform {
  required_version = ">= 1.5"

  # State lives alongside Farm's other Terraform, keyed under a shared/ prefix. This root is
  # non-workspaced: it provisions a single project shared across all environments.
  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/farm/v1/shared"
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.25"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.8"
    }
    github = {
      source  = "integrations/github"
      version = "~> 6.11"
    }
  }
}

# Module imports

module "common" {
  source = "../common"
}

# Providers

# Primary Google provider
provider "google" {
  project = module.common.gcp_meta_project_id
  region  = module.common.gcp_primary_location
}

# GitHub provider for the account-level repo objects this root owns: the releases
# repo and the Actions secrets. The repository itself is managed by the
# .github/config root; here it is only referenced as data.

variable "gh_token" {
  description = "Organization-owned GitHub token."
  type        = string
  sensitive   = true
}

provider "github" {
  owner = module.common.gh_organization_name
  token = var.gh_token
}

data "github_repository" "this" {
  full_name = "${module.common.gh_organization_name}/${module.common.gh_repo_name}"
}

