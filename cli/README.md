# `ms-farm` — the Farm CLI

A small Kotlin/JVM command-line client for the Farm service. It signs you in
with your `medusa.software` Google account and drives the counter from a
terminal.

```
ms-farm login        # sign in (opens a browser) and cache the session
ms-farm increment    # +1, prints the new count
ms-farm decrement    # -1, prints the new count
ms-farm get          # print the current count
ms-farm logout       # forget the cached session on this machine
```

## Install (Homebrew)

```
brew install medusa-software-hq/tap/farm
```

It depends on `openjdk@21` (installed automatically) and runs as `ms-farm`.

## How auth works

`login` runs the standard **installed-app OAuth flow**: it opens your browser to
Google, catches the redirect on an ephemeral `127.0.0.1:<port>` loopback, and
exchanges the code (PKCE) for tokens. It caches a refresh token under
`~/.config/ms-farm/credentials.json` (dir `0700`, file `0600`) and silently
refreshes the short-lived ID token as needed — you only re-`login` if the
refresh token is revoked.

Each request sends your Google **ID token** as `Authorization: Bearer …`. The API
accepts it because its audience is the Farm CLI's Desktop OAuth client (one of
the API's allowed audiences) and it carries the `medusa.software` hosted-domain
claim.

## Environments

The CLI runs against **one environment per invocation**, chosen once by
`FARM_ENVIRONMENT` (`AWS_PROFILE`-style — no per-command flag). Absent → prod.
Each environment has its **own** partitioned session under
`~/.config/ms-farm/<env>/`, its own backend, and its own Desktop OAuth client,
so prod and staging can never share credentials. Non-prod prints a dim marker
(`[staging]`) to stderr.

```
ms-farm increment                              # prod (default)
FARM_ENVIRONMENT=staging ms-farm increment  # staging (needs the staging OAuth secret)
```

## Local development

Point the CLI at a local backend with `FARM_ENVIRONMENT=local`, supplying the
config dir and port:

```
FARM_ENVIRONMENT=local \
FARM_LOCAL_CONFIG_PATH=/tmp/ms-farm-local \
FARM_API_LOCAL_PORT=8081 \
  ms-farm get
```

The local backend uses no-op auth, so `get`/`increment`/`decrement` work without
`login` (the CLI still attaches a token; the no-op decorator ignores it). A local
build has no OAuth client secret baked in; set the per-env secret env var
(`FARM_CLI_OAUTH_CLIENT_SECRET` for prod, `…_STAGING` for staging) if you need
`login` to run against a real environment from a dev build.

Common tasks (via [Task](https://taskfile.dev)):

```
task cli:compile      # compile
task cli:test         # unit tests
task cli:lint         # detekt
task cli:run -- get   # build + run locally
```
