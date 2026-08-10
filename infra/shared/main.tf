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

