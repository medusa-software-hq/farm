# backend:local

The one-process local stack: the API and the Fibonacci worker in a single JVM over one in-memory
store, talking through a local Temporal dev server. `StartFibonacci` flows API → dev server →
in-process worker → shared store → `ListFibonacci`.

It connects to Temporal in the insecure local mode (`localhost:7233`, namespace `default`, no API
key). The Temporal dev server is optional: if it is not running, the worker connection fails, the
stack logs it and keeps serving — `ListFibonacci` still works, only `StartFibonacci` degrades.

Run the full stack (Temporal dev server, this backend, and the frontend) with `task dev` from the
repo root; that needs the `temporal` CLI on PATH.
