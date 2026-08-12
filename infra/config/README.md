# Per-environment configuration

The single source of truth for values that must stay identical between Terraform
and non-Terraform consumers (today, the `ms-farm` CLI): per-environment OAuth
client ids and the API host.

- `config.input.json` — hand-edited static constants plus the shared
  project/variant/domain they derive from.
- `config.json` — the fully resolved bundle (inputs + derived `api_host`),
  committed and read directly by consumers that don't run Terraform.
- `main.tf` recomputes the bundle from the input and aborts the plan when the
  committed file disagrees, so a stale file can't reach CI.

Regenerate the committed file after editing the input with `task config:regenerate`.
