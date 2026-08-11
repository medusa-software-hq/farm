# Configuration

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/farm/v1/backend/api/foundation"
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
    neon = {
      source  = "kislerdm/neon"
      version = "~> 0.9"
    }
  }
}

# Module imports

module "common" {
  source = "../../infra/common"
}

# Providers

variable "gcp_project_id" {
  description = "GCP project ID."
  type        = string
}

variable "neon_api_key" {
  description = "Neon API key used to provision the serverless Postgres project."
  type        = string
  sensitive   = true
}

variable "github_app_pem" {
  description = "PKCS#8 PEM content of the GitHub App private key, injected into Cloud Run."
  type        = string
  sensitive   = true
}

variable "github_app_client_id" {
  description = "GitHub App client id (public, but per-environment)."
  type        = string
}

# Primary Google provider
provider "google" {
  project = module.common.gcp_meta_project_id
  region  = module.common.gcp_primary_location
}

# Neon provider for serverless Postgres provisioning
provider "neon" {
  api_key = var.neon_api_key
}

