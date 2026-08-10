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
