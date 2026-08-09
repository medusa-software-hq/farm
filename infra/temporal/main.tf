terraform {
  required_version = ">= 1.5"

  # State lives alongside Farm's other Terraform, keyed under a temporal/ prefix.
  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "repos/farm/temporal"
  }

  required_providers {
    temporalcloud = {
      source  = "temporalio/temporalcloud"
      version = ">= 0.6.0"
    }
  }
}

# Bootstrap credential — an account-level Temporal Cloud API key used ONLY to provision (create the
# namespace + service account + the worker's scoped key). Never committed; supply it at apply time:
#   export TF_VAR_temporal_cloud_api_key="$(...your account API key...)"
# Generate it once in the Temporal Cloud console (Settings → API Keys, or a service account with
# account 'admin'). This is separate from the namespace-scoped key this config mints for the worker.
variable "temporal_cloud_api_key" {
  description = "Bootstrap Temporal Cloud API key (account admin), used only to provision."
  type        = string
  sensitive   = true
}

variable "temporal_cloud_account_id" {
  description = "Temporal Cloud account id (shown in the console, e.g. kr9zt). Guards against provisioning into the wrong account."
  type        = string
  default     = "kr9zt"
}

variable "regions" {
  description = "Temporal Cloud region(s) for the namespace, e.g. [\"aws-us-east-1\"] or [\"aws-eu-central-1\"]."
  type        = list(string)
  default     = ["aws-us-east-1"]
}

provider "temporalcloud" {
  api_key            = var.temporal_cloud_api_key
  allowed_account_id = var.temporal_cloud_account_id
}
