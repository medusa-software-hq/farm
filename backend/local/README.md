# backend:local

The one-process local stack: the API and an in-process Temporal worker in a single JVM over one
in-memory store, talking through a local Temporal dev server. Linking an org (`ms-farm link-org`)
flows API → dev server → in-process worker → shared store, so the synced repos land in the same
table the API's `ListRepositories` reads.

It connects to Temporal in the insecure local mode (`localhost:7233`, namespace `default`, no API
key) and needs the dev server running.

Run the full stack (Temporal dev server, this backend, and the frontend) with `task dev` from the
repo root; that needs the `temporal` CLI on PATH.
