# Farm ↔ Temporal Cloud

Minimal, trial-oriented integration: Terraform provisions a Temporal Cloud namespace + a
namespace-scoped service account and API key; [`backend/temporal-demo`](../../backend/temporal-demo)
proves Farm can connect and run a workflow. Uses **API-key auth over TLS** (not mTLS).

## 1. Where you fill the API key

Provisioning needs a **bootstrap** account-level API key (used only by Terraform to create things).
Generate it once in the Temporal Cloud console (**Settings → API Keys**, or a service account with
account role `admin`), then hand it to Terraform **at apply time** — never commit it:

```bash
export TF_VAR_temporal_cloud_api_key="tmprl_...your account API key..."
```

This is separate from the key the config *mints* for the worker (below).

## 2. Provision

```bash
cd infra/temporal
terraform init                       # GCS backend (needs GCP ADC)
terraform plan                       # review
terraform apply
```

Optional: set the region (default `aws-us-east-1`) with
`-var 'regions=["aws-eu-central-1"]"'`, or the account guard with `-var temporal_cloud_account_id=…`.

## 3. Prove it works

Take the three connection values from the outputs and run the demo:

```bash
export TEMPORAL_ADDRESS="$(terraform output -raw grpc_address)"
export TEMPORAL_NAMESPACE="$(terraform output -raw namespace_id)"
export TEMPORAL_API_KEY="$(terraform output -raw worker_api_key)"   # the minted, namespace-scoped key

cd ../.. && ./gradlew :backend:temporal-demo:run
```

Expected:

```
Hello, Farm — from Temporal Cloud.
OK — reached Temporal Cloud namespace 'farm.<account>' and completed a workflow.
```

You'll also see the run in the Temporal Cloud UI (Workflows).

## Notes

- The minted `worker_api_key` is **sensitive** — it lives in Terraform state (GCS) and is printed
  only via `terraform output -raw`. For real deployment, push it into **GCP Secret Manager** and
  inject it into the worker via the Workload profile's `secret_env_vars`; don't keep it in plaintext.
- The key's `expiry_time` is set to the trial window (`2026-11-07`) as a forcing function — rotate
  before then.
- The demo is intentionally throwaway; the real worker (Farm's `backend/worker`) reuses the same
  `TEMPORAL_ADDRESS` / `TEMPORAL_NAMESPACE` / `TEMPORAL_API_KEY` contract.
