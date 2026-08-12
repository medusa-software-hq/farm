# Deployment configuration

The one source of the deployment values that must stay identical between the
Terraform that provisions the environments and the artifacts built from this
repo (the API, the worker runner, the CLI) — the values neither side may
hardcode independently without drifting.

They are the `local`s in `main.tf`. `config.json` is emitted from them and
committed so consumers that don't run Terraform can read it directly. Regenerate
it after editing the locals with `task config:regenerate`; CI re-emits and fails
on any diff, so the committed copy can't go stale.
