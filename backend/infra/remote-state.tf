# The worker-temporal-api-key secret lives in the cross-environment shared project (infra/shared),
# so the API mounts it and grants its own SA access to it from there. The shared project's id comes
# from that root's state; the CI/CD SA can read it because state access is scoped to
# projects/farm/v1/.
data "terraform_remote_state" "shared" {
  backend = "gcs"

  config = {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/farm/v1/shared"
  }
}
