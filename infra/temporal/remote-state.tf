# The cross-environment shared project (infra/shared) holds the worker-temporal-api-key secret, so
# both prod and staging read one key from one place. Its project id comes from that root's state.
data "terraform_remote_state" "shared" {
  backend = "gcs"

  config = {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/farm/v1/shared"
  }
}

locals {
  shared_project_id = data.terraform_remote_state.shared.outputs.shared_project_id
}

# Each environment's API service account (which starts Fibonacci workflows) needs read access to the
# worker key. Its email is published by that env's backend/api/foundation root. Those two states share
# one gcs prefix and are separated by workspace: prod is the default workspace (default.tfstate),
# staging is the "staging" workspace (staging.tfstate). Reading them here keeps the grant's members
# de-hardcoded. backend/infra must apply first so primary_service_sa_email exists.
data "terraform_remote_state" "backend_api_prod" {
  backend   = "gcs"
  workspace = "default"

  config = {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/farm/v1/backend/api/foundation"
  }
}

data "terraform_remote_state" "backend_api_staging" {
  backend   = "gcs"
  workspace = "staging"

  config = {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/farm/v1/backend/api/foundation"
  }
}

locals {
  api_service_account_emails = toset([
    data.terraform_remote_state.backend_api_prod.outputs.primary_service_sa_email,
    data.terraform_remote_state.backend_api_staging.outputs.primary_service_sa_email,
  ])
}
