# Farm

**The successor to Flow** — a ground-up rewrite of the autonomous coding agent,
rebuilt on the foundations Flow taught us to want.

Flow proved the thesis: an agent can take a GitHub issue, implement it, and open a
pull request — reliably enough to build *itself*, night after night. Farm keeps that
thesis and changes what sits underneath it, trading conventions Flow grew into over
time for a handful of decisions made deliberately, up front.

## The loop

Label an issue `farm:ready` and Farm takes it: it clones the repository, works the
issue with the agent, pushes a branch, and opens a pull request. Review it as you
would anyone's. Asking for changes starts another run — a fresh one, given what the
last run did and what the review said, rather than a conversation resumed — and
merging ends the session. A check the branch requires going red starts a run of its
own, without waiting to be asked. Nothing merges itself.

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

The loop above runs, end to end, and a system test proves it does: it files an issue
against a throwaway repository, waits for the agent to work it, reviews the pull
request, waits for the fixup, and merges — against real GitHub and the real agent,
with nothing stubbed.

What is not built yet: the self-healing trunk described above. Farm also learns that
something changed by looking on a timer rather than by being told, so the delay
between labelling an issue and Farm noticing is minutes rather than seconds.
