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

## The private key must be PKCS#8

The app's signing key must be supplied as an **unencrypted PKCS#8** PEM (a `BEGIN PRIVATE KEY`
block). GitHub issues App keys in PKCS#1 (`BEGIN RSA PRIVATE KEY`); convert once, out of band:

```
openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pk8.pem
```

The key is parsed when the App client is built, not per request, so a wrong format fails at startup
rather than on the first call.
