# `backend:github-client`

A GitHub REST API client that acts **as the Farm app**. It is organised around GitHub's own auth
model, in three surfaces:

- an **App** surface, driven by the app's private-key JWT, for the endpoints only the app itself can
  reach — resolving an org's installation and minting installation access tokens;
- a **resources** surface that is *credential-agnostic*: it authenticates through a token seam, so the
  same code works whether the token is an installation token or (later) a user's PAT;
- an **installation** surface — the resources plus the endpoints an installation token additionally
  unlocks — handed out per installation id (the durable handle; org logins can be renamed), each
  refreshing its own token behind the seam.

The type names all begin `Gh`.

## The Farm App

The GitHub App Farm acts as. Every environment registers its own — production, staging, the one a
local stack uses, and the ephemeral one the system tests drive — and a registration is a
realization of the same App: the same permissions, on whichever organizations are linked to it.

Contents, issues and pull requests, each read and write. That is what the work needs: cloning a
repository, reading the issue it was pointed at, pushing what the agent changed, and opening and
following the pull request that carries it.

Two other Apps appear alongside it and are not realizations of it: the one the system tests act as,
which drives Farm from outside rather than being Farm, and the one that publishes the CLI's
releases.

## The private key must be PKCS#8

The app's signing key must be supplied as an **unencrypted PKCS#8** PEM (a `BEGIN PRIVATE KEY`
block). GitHub issues App keys in PKCS#1 (`BEGIN RSA PRIVATE KEY`); convert once, out of band:

```
openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pk8.pem
```

The key is parsed when the App client is built, not per request, so a wrong format fails at startup
rather than on the first call.
