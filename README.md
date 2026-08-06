# Farm

**The successor to Flow** — a ground-up rewrite of the autonomous coding agent,
rebuilt on the foundations Flow taught us to want.

Flow proved the thesis: an agent can take a GitHub issue, implement it, and open a
pull request — reliably enough to build *itself*, night after night. Farm keeps that
thesis and changes what sits underneath it, trading conventions Flow grew into over
time for a handful of decisions made deliberately, up front.

## Founding decisions

**Temporal from day one.** Flow modelled its issue → PR → merge → deploy lifecycle
as an ad-hoc state machine stitched together from a database and a reconciler loop.
Farm expresses it as durable [Temporal](https://temporal.io) workflows instead:
retries, timeouts, human-in-the-loop waits, and long-running orchestration become the
platform's responsibility rather than something reinvented for each feature. Farm is a
first-class consumer of the org's self-hosted Temporal instance.

**Self-healing at the trunk.** The costly mistakes in infrastructure-as-code slip past
`validate` — you only learn a change is wrong when you `apply` it, after the merge.
Farm watches its own trunk pipeline: when a post-merge apply (or any downstream check)
fails, it treats the failure as a fresh task and fixes it, instead of waiting for a
human to chase the missing grant. The loop a person runs today — *watch the pipeline,
read the error, open the fix* — becomes the agent's own.

## Status

Early. Farm today is a scaffold, not yet a working agent — this README is the
direction, not a description of what already runs.
