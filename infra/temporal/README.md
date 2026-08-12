# Farm ↔ Temporal Cloud

Terraform provisions a Temporal Cloud namespace + a namespace-scoped service account and API key, then
publishes that key into **GCP Secret Manager** so the real worker (Farm's `backend/worker`) can read it
uniformly. Uses **API-key auth over TLS** (not mTLS). The worker runs Farm's repo-sync workflows
against the namespace.

## 1. Where you fill the API key

Provisioning needs a **bootstrap** account-level API key (used only by Terraform to create things).
Generate it once in the Temporal Cloud console (**Settings → API Keys**, or a service account with
account role `admin`), then hand it to Terraform **at apply time** — never commit it:

```bash
export TF_VAR_temporal_cloud_api_key="tmprl_...your account API key..."
```

This is separate from the key the config *mints* for the worker (below). GCP credentials come from
Application Default Credentials (both the GCS state backend and the `google` provider).

## 2. Provision

```bash
cd infra/temporal
terraform init                       # GCS backend + providers (needs GCP ADC)
terraform plan                       # review
terraform apply
```

Optional: set the region (default `aws-us-east-1`) with
`-var 'regions=["aws-eu-central-1"]"'`, the account guard with `-var temporal_cloud_account_id=…`, or
the secret's GCP project with `-var gcp_project_id=…` (defaults to prod, `ms-farm-11efee2b`).

Apply writes the minted key to Secret Manager as **`worker-temporal-api-key`** in `var.gcp_project_id`.
The non-secret connection values are the outputs:

```bash
terraform output -raw grpc_address    # TEMPORAL_ADDRESS, e.g. farm.kr9zt.tmprl.cloud:7233
terraform output -raw namespace_id    # TEMPORAL_NAMESPACE, e.g. farm.kr9zt
```

## Notes

- The minted key lives in Terraform state (GCS) and in Secret Manager. Consumers should read it from
  Secret Manager, not from `terraform output`. Its `expiry_time` is set to the trial window
  (`2026-11-07`) as a forcing function — rotate before then.
- **One namespace, shared across environments for now.** The key is written to the **prod** project
  (`ms-farm-11efee2b`) only. A dedicated staging Temporal namespace (and a staging-project copy of the
  secret) is a follow-up; when it lands, this config grows a per-environment namespace + service
  account the way `infra/common` splits prod/staging elsewhere.
