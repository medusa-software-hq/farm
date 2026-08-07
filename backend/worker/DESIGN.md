# `backend/worker` — the Farm worker, on Temporal

> Status: **design decided** + compiling scaffold. The seven open questions the first draft raised are
> now resolved (see §6), and the workflow model has been refined into three workflows split at the
> merge line. Workflow/activity **interfaces** and the process bootstrap are real and compile; activity
> **bodies** and a few workflow waits are stubbed (`TODO`). This document is the authoritative spec; the
> Kotlin under `src/main` is the shape it lands in.

This module is Farm's autonomous-coding-agent worker — Flow's `worker/` **re-expressed on Temporal**.
Flow modelled the `issue → PR → merge → deploy` lifecycle as an ad-hoc state machine stitched from a
Postgres schema plus an externally-triggered reconciler loop, with the per-repo mutex enforced by
partial unique indexes and an in-process lock. Farm hands that orchestration to Temporal: retries,
timeouts, human-in-the-loop waits, the per-repo mutex, and long-running coordination become the
platform's job. The DB stops being the state machine and becomes the **domain store + read model**.

It also closes the gap the README names: Flow stops at "issue closed" and never converges the running
system onto a merge, and a red post-merge check wedges the repo until a human clears it. Farm's
pipeline continues **past merge** into deploy/apply and, on failure, **self-heals** — but with a
crucial refinement over Flow's operational tax: **a failure releases the repo mutex by default and is
never allowed to routinely wedge the repo** (§2.3, §6.6).

Two ideas run through the whole design and are worth stating up front, because §2 and §6 keep leaning
on them:

- **The mutex is a workflow boundary, not a signal handshake** (§2, section B). The per-issue pipeline
  is split at the merge line into a **mutex-held `BuildWorkflow`** and a **detached
  `PostMergeWorkflow`**. The coordinator simply *awaits an owned `BuildWorkflow` child*, which returns
  exactly at merge — so the mutex is implicit in that await, with no mid-life "mutex released" signal
  and no ABANDON-the-whole-pipeline bookkeeping.
- **Farm is a co-author, not an automaton** (§2.5, "the reactor stance"). The pipeline reacts to the
  *actual* PR/GitHub state on every step; a human pushing a fixup, merging, or closing a Farm-owned PR
  is never a special case — it is just "state changed, react."

There is also a **second gate** the mutex does *not* cover, and the design keeps it distinct: the
**trunk-health merge gate** (§2). The mutex serializes *build starts*, but a build releases the mutex at
merge and a later build runs in parallel while the prior `PostMergeWorkflow` is still watching trunk —
so a later build can reach "ready to merge" just as trunk goes red. A build therefore must clear a
separate **`awaitTrunkHealthy()`** gate before it merges, so Farm never stacks a merge onto a red or
still-deploying default branch. The two gates answer different questions: the mutex asks *"may I
start?"*, the trunk-health gate asks *"may I land?"*.

---

## 0. Process shape — arg-less worker, admin-only CLI

Three processes, kept crisply separate:

| Process | What it is | How it starts |
|---|---|---|
| **`backend/worker`** (this module) | The long-running Temporal worker. Registers workflow + activity impls on **two task queues** (§2.4) and runs them. | **Arg-less, env-configured.** `main()` reads env → connects to Temporal → `factory.start()`. Container-style, like Flow's worker image. |
| **`backend/api`** | Armeria gRPC service (exists today as the counter demo). The **only** thing that *starts/steers* workflows: it holds a `WorkflowClient`, and signals/queries workflows + reads the DB read model on behalf of clients. It also terminates GitHub webhooks and forwards them as signals. | Its own deployable (`backend/api/gcp`). |
| **`ms-farm`** (`cli/`) | Admin helper — a web-console alternative for administration. Talks to `backend/api` over gRPC. **Never** runs workflows and is **not** the worker entrypoint. | User-invoked CLI. |

The worker takes **no arguments**. Contrast Flow, whose worker was the `flow work` CLI subcommand
(`WorkCommand`). Everything that was a CLI flag or `WrkConfig` field is now an env var read by
[`WorkerConfig.fromEnvironment`](src/main/kotlin/software/medusa/farm/worker/WorkerConfig.kt).

```
TEMPORAL_ADDRESS      (default 127.0.0.1:7233)   # org demo convention
TEMPORAL_NAMESPACE    (default farm)             # farm | farm-staging (§6.1)
DATABASE_URL          (required)                 # full JDBC URL incl. creds + sslmode
FARM_GITHUB_APP_ID    (required)                 # from Secret Manager (§6.4)
FARM_GITHUB_APP_PEM   (required)                 # PKCS#8 GitHub App private key, from Secret Manager
FARM_ENGINE           (default claude)           # claude only, to start (§6.3)
FARM_ENGINE_API_KEY   (optional)
FARM_ORG_OWNER        (required)                 # fences all GitHub ops to one org
```

Task-queue names are not env-configured knobs any more: the worker registers **both** the
`farm-pipeline` and `farm-engine` queues (§2.4), and `TaskQueues` is the single shared source of truth
the api reads when it starts a workflow.

**Namespace.** The worker connects to a namespace but never creates one — namespaces are created
declaratively by **temporal-instance issue #8's Terraform consumption module**, not by a manual
`temporal operator namespace create` (§6.1). Farm therefore depends on #8 delivering the `farm` and
`farm-staging` namespaces before it can run in an environment.

---

## 1. Module & process layout

New Gradle module `:backend:worker` (package `software.medusa.farm.worker`), wired into the unified
root: added to `settings.gradle.kts` `include(...)`, to the root `Taskfile.yml` includes +
`*-all` aggregates, with its own `Taskfile.yml` and `gradle.module.yaml` mirroring `backend/api`.
It applies only `kotlin.jvm` + `application` + `sqldelight` + `jib`; the Java 21 toolchain, ktfmt and
detekt come from the root `subprojects {}` block. Temporal deps are added to the version catalog
(`temporal = "1.37.0"`; `temporal-sdk`, `temporal-kotlin`, `temporal-testing`).

```
backend/worker/
  build.gradle.kts            kotlin.jvm + application + sqldelight + jib; temporal-sdk/-kotlin
  Taskfile.yml                checkFormatting/lint/compile/test/run  (../../gradlew)
  gradle.module.yaml          analyze/test phases for the repo tooling
  DESIGN.md                   this document
  src/main/kotlin/software/medusa/farm/worker/
    main.kt                   arg-less fun main() -> TemporalWorkerHost(config).run()
    WorkerConfig.kt           env → config object (fromEnvironment)
    TaskQueues.kt             the two shared task-queue name consts (pipeline + engine)
    TemporalWorkerHost.kt     stubs → client → WorkerFactory → register two workers → start → drain
    model/Model.kt            payload data classes + enums (workflow/activity/signal/query IO)
    workflow/
      Workflows.kt                 @WorkflowInterface RepoCoordinator/Build/PostMerge
      RepoCoordinatorWorkflowImpl  per-repo mutex holder; awaits one owned BuildWorkflow at a time
      BuildWorkflowImpl            mutex-held: prepare → engine → PR → pre-merge fix loop → merge
      PostMergeWorkflowImpl        detached: observe deploy → green? DONE : post-merge heal (new PR)
    activity/
      Activities.kt                @ActivityInterface Repo/Engine/GitHub/Deploy/DomainStore
      impl/ActivityImpls.kt        stub impls (TODO bodies); holds the worker-singleton token minter
      impl/WorkerDomainStore.kt    Hikari + Flyway + SQLDelight (mirrors PostgresCounterStore)
  src/main/sqldelight/software/medusa/farm/worker/db/
    Pipeline.sq  Session.sq        compile-time schema + queries (FarmWorkerDatabase)
  src/main/resources/db/migration/
    V1__create_worker_schema.sql   Flyway-owned runtime schema (mirrors the .sq CREATE TABLEs)
  src/test/kotlin/.../WorkerConfigTest.kt
```

**Bootstrap** ([`TemporalWorkerHost`](src/main/kotlin/software/medusa/farm/worker/TemporalWorkerHost.kt)):
`WorkflowServiceStubs.newServiceStubs(target = TEMPORAL_ADDRESS)` → `WorkflowClient.newInstance(namespace = TEMPORAL_NAMESPACE)`
→ `WorkerFactory.newInstance(client)` → **two workers on one factory**:

- `factory.newWorker("farm-pipeline")` — registers **all three** workflow impls
  (`RepoCoordinatorWorkflowImpl`, `BuildWorkflowImpl`, `PostMergeWorkflowImpl`) plus the **light
  activities** (Repo/GitHub/Deploy/DomainStore). Its own, generous `maxConcurrentActivityExecutionSize`.
- `factory.newWorker("farm-engine")` — registers **only** the `EngineActivities` impl, with its own,
  much smaller `maxConcurrentActivityExecutionSize`, sized for a handful of 2-hour subprocess runs.
  `runEngine` is routed here by `ActivityOptions.setTaskQueue("farm-engine")` on its workflow stub.

then `factory.start()`, with a JVM shutdown hook calling `factory.shutdown()` + `service.shutdown()`
for graceful drain. The two-queue split lives in **one process** today (§6.2); registering the engine
worker on a second `factory.newWorker(...)` is exactly the seam that lets the engine worker relocate to
a separate fleet later without touching workflow code. The frontend is **plaintext, VPC-private** (no
TLS/auth), so the worker must run inside the same VPC.

---

## 2. Temporal workflow model

Flow had **two** state machines — the issue-pipeline (`InProgress → PrOpen → AwaitingMergeChecks →
Done | Failed`) and the session (`Pending → Running → Completed | Failed | Aborted`) — plus a
reconciler that polled GitHub to advance them. Farm keeps Temporal timers + signals as the "poll and
advance" engine, but expresses the lifecycle as **three cooperating workflows**, split at the merge
line. The split is what makes the per-repo mutex a clean workflow boundary (section B) rather than a
signal handshake.

### Section B — the mutex is a WORKFLOW boundary

The first draft had a single `PipelineWorkflow` per issue, started `ABANDON` by the coordinator, which
signalled the coordinator (`onPipelineMutexReleased`) the instant it merged so the coordinator could
pick the next issue while the abandoned child watched post-merge. That worked but carried three warts:
a mid-life "mutex released" signal, an ABANDON-ed child the coordinator no longer owned, and orphan
tracking to reason about. All three vanish if you **split the pipeline at the merge line into two
workflows** and let the mutex simply be "the coordinator awaits one *owned* child":

```
RepoCoordinatorWorkflow  (id = repo:<owner>/<name>, THE MUTEX):
  loop:
    issue = nextUnblockedReadyIssue()
    build = startChild(BuildWorkflow, issue)   // OWNED, awaited (no ABANDON)
    build.awaitResult()                        // ← the mutex IS this await; it ends at merge
    // pick the next issue

BuildWorkflow  (id = pipeline:<repo>#<issue>, MUTEX-HELD):
  prepare → engine → open PR → (pre-merge fix loop) → await merge (by anyone)
  on merge:
    startChild(PostMergeWorkflow, mergeSha, ParentClosePolicy.ABANDON)   // the one honest ABANDON
    return                                                               // ← releases the mutex

PostMergeWorkflow  (id = postmerge:<repo>#<issue>, MUTEX-FREE, detached):
  observe/await deploy → green ? DONE : post-merge heal (NEW PR) → …
```

The win: **no mid-life mutex-release signal, no ABANDON-the-whole-pipeline, no orphan tracking.** The
mutex is implicit in awaiting an owned `BuildWorkflow` that returns *exactly* at merge. The single
remaining `ParentClosePolicy.ABANDON` is honest — it detaches the genuinely-independent post-merge work
so it outlives the coordinator's `continueAsNew`. And `continueAsNew` on the coordinator happens
*between* picks, when no `BuildWorkflow` child is active, so the owned-child model stays clean across
history truncation.

### 2.1 `RepoCoordinatorWorkflow` — the per-repo mutex, natively

```kotlin
@WorkflowInterface
interface RepoCoordinatorWorkflow {
  @WorkflowMethod fun coordinate(repo: RepoRef)
  @SignalMethod  fun onReadyIssuesChanged()   // api nudge (webhook: new flow:ready issue)
  @SignalMethod  fun approve()                // clears a circuit-breaker pause (§2.3)
  @SignalMethod  fun onTrunkStatusChanged(sha: String, healthy: Boolean)  // trunk-health, §2.3/§2.4
  @QueryMethod   fun status(): RepoCoordinatorStatus
}
```

**Workflow id = `repo:<owner>/<name>`.** Temporal guarantees a single running execution per workflow
id, so *this is the mutex* — no partial unique index, no in-process `RepoLock`, no advisory-lock TODO
for multi-instance (the exact gap Flow's `InMemoryRepoLock` left open). The coordinator loops: discover
the next unblocked `flow:ready` issue → start **one owned** child `BuildWorkflow` →
`build.awaitResult()` → pick the next. The `awaitResult()` **is** the mutex hold, and it returns the
moment the `BuildWorkflow` merges (§2.2), releasing the repo for the next issue while the detached
`PostMergeWorkflow` watches trunk. When idle it parks on `Workflow.await(idlePollInterval) { wakeUp }`,
woken by `onReadyIssuesChanged` or the timer. `Workflow.continueAsNew` bounds history after N builds —
always *between* picks, so no child is in flight at the boundary.

Note the coordinator no longer has an `onPipelineMutexReleased` signal: the mutex release is not an
event to be told about, it is the return of the awaited child.

**Trunk health has exactly one source of truth: the `getTrunkHealth(repo)` read activity**, which
computes the **literal GitHub default-branch state** — the combined status of the runs linked to trunk's
current tip commit, *right now*, **whoever authored it** (the mechanical definition is in §2.2). The
build's merge gate (§2.2) calls it directly. This grounding is a co-author requirement (§2.5): a human
can merge an urgent out-of-band fixup (a non-Farm PR, with no `PostMergeWorkflow` watching it); if *that*
breaks trunk, a health value fed only by Farm's own post-merge workflows would miss it and Farm would
merge onto a broken trunk. Reading the branch itself can't be fooled that way.

The **`onTrunkStatusChanged(sha, healthy)`** signal — fed by trunk `check_run`/`workflow_run`/deploy
webhooks for **any** commit/author via the api — is **only a low-latency hint**: it lets the api nudge
a waiting build to re-read sooner than its backstop timer would. It is **not** cached as an authoritative
value and is **not** relayed coordinator→build as the gate's input; correctness never depends on the
hint arriving, only latency does. (The coordinator may keep the last hint for observability in its
`status()` query, but that value is explicitly not what the gate trusts.)

`PostMergeWorkflow` is likewise **not** the authority for trunk health (§2.3) — it is a *consumer* of
trunk state and the *self-healer of Farm's own merges*. The gate trusts `getTrunkHealth`, i.e. the
literal branch, not Farm's memory of what it merged.

**Circuit breaker (§6.6).** The coordinator tracks *consecutive* `BuildWorkflow` failures for the repo.
After **K straight failures** (default small, e.g. 3) it does **not** keep re-picking into a wall — it
pauses re-pick and parks on `Workflow.await { approved }`, requiring a human `approve` signal (relayed
by the api) to resume. This is a *soft* escalation for a repo that is clearly stuck, deliberately
distinct from Flow's hard default of wedging on *every* failure.

### 2.2 `BuildWorkflow` — the mutex-held half (prepare → merge)

```kotlin
@WorkflowInterface
interface BuildWorkflow {
  @WorkflowMethod fun run(input: PipelineInput): BuildResult
  @SignalMethod  fun approve()                       // human gate (risk-gated merge / self-heal)
  @SignalMethod  fun abort(reason: String)           // cancels the in-flight engine activity
  @SignalMethod  fun onCheckCompleted(update: CheckUpdate)  // pre-merge checks (webhook → api)
  @SignalMethod  fun onPrMerged(mergeCommitSha: String)     // merged BY ANYONE
  @SignalMethod  fun onPrClosed()                    // a human abandoned the PR (§2.5)
  @SignalMethod  fun onBranchUpdated()               // a human pushed to the branch (§2.5)
  @QueryMethod   fun status(): PipelineStatus
}
```

**Workflow id = `pipeline:<owner>/<name>#<issue>`.** This workflow *holds the mutex* for its entire
life — the coordinator is blocked in `awaitResult()` on it — so it must end promptly at merge. Its
stages:

```
PREPARING → ENGINE_RUNNING → PR_OPEN → AWAITING_MERGE_CHECKS ─(green)─▶ REBASE → awaitTrunkHealthy()
                                          │                                              │
                                          └─(red)─▶ PRE-MERGE FIX LOOP ─┘ (bounded)       ▼
   any stage → FAILED  (mutex released; never wedges — §6.6)               MERGED → (return)
```

`run()` is straight-line orchestration (see
[`BuildWorkflowImpl`](src/main/kotlin/software/medusa/farm/worker/workflow/BuildWorkflowImpl.kt)):

```
prepare → engine → open PR → pre-merge fix loop (until green) → rebase onto latest trunk
  → awaitTrunkHealthy()      // ← trunk-health merge gate: don't land on a red/clogged trunk
  → arm merge (risk-gated, §2.4) → await merge (by anyone)
  → startChild(PostMergeWorkflow, ABANDON) → return BuildResult(MERGED, mergeSha)
```

That `return` is the mutex release.

**Trunk-health merge gate (the second gate).** After pre-merge checks are green *and* the branch has
been rebased onto the latest trunk, the build calls **`awaitTrunkHealthy()`** *before* the merge step,
reading trunk health via `getTrunkHealth` (§2.1). It resolves immediately in the common case (trunk is
already green by the time this build is ready → full parallelism, no wait). It **waits** only when trunk
is currently red or still deploying — which is the correct exception: **stop stacking merges onto a
broken default branch** until it recovers. Because the build **holds the mutex** while it waits here, a
red trunk naturally **pauses the whole repo's merges** — free backpressure, no extra mechanism, and it
fires for a red trunk from **any** cause (a Farm merge, an unrelated stacked merge, or a human's
out-of-band commit). The wait is **bounded**: if trunk stays red past the bound, the build **escalates
to a human** (tying into the §6.6 circuit breaker / self-heal cap) rather than blocking the repo forever.

**What counts as a "red trunk" — mechanical, no per-repo declaration.** A **trunk-health-relevant run**
is any workflow run (or check-run) with `head_branch == default_branch` **and**
`head_sha == the current trunk tip`. That single rule is **opt-*out***, not opt-in: *everything* that
ran against the tip counts, and a specific non-load-bearing workflow can be **excluded individually** if
it ever over-triggers — there is no set of "gating workflows" to declare and keep in sync. The rule
falls out cleanly: it **excludes PR checks** (different branch) and **excludes stale runs** (different
commit); a workflow that simply didn't run against the tip is **vacuous, not red**. `getTrunkHealth(repo)`
computes exactly this off the tip commit's runs/check-runs.

Trunk health is **tri-state**, not binary:

- **red** — ≥1 tip-linked run *concluded* in a **real** failure (see flaky handling below). The gate
  waits (backpressure), bounded → escalate.
- **pending** — some tip-linked run is still in progress. The gate **waits** (up to the cap) and treats
  it as neither red nor green. This is the normal state *right after* a merge: the new tip's deploys are
  pending until they conclude.
- **green** — all tip-linked runs concluded and none failed (a tip with no relevant runs is vacuously
  green — absence never blocks).

**Flaky vs. real failure.** A concluded failure counts as **red only after re-run discrimination** — a
single flaky/transient run (the GCS-503 / GitHub-Actions-outage / flaky-test class) is **not** red on
its own; it is re-run first and only a *confirmed* failure flips trunk to red. This is deliberately the
mechanism that keeps the gate from **wedging the repo on noise**: without it, one infrastructure hiccup
on trunk would freeze every merge in the repo behind a false red.

**Break-glass override — a safe default, not a one-way door.** The gate is the right default, but its
false-positives (whatever automation's flaky-detection still gets wrong) need a human recourse. An admin
can **override the gate** through the **same signal path as `approve`/`abort`** (ms-farm / console →
`backend/api` → workflow signal), at three scopes:

- **per-PR** — "land this one anyway" (overrides the gate for a single build);
- **per-repo** — "gating off for this repo";
- **global-temporary** — "gating off until I say."

Two guardrails make the escape hatch safe: every override is **audited** (who / when / why — a forced
merge past a red trunk must leave a record), and the **global scope is time-boxed** (it auto-expires, so
gating is never silently left off forever). This is the human's recourse for the gate's false-positives,
consistent with the co-author stance (§2.5): a person can always step in and land a change Farm is
holding.

**Pre-merge fix loop (§6.6, distinct heal loop #1).** While `stage < MERGED`, if the PR's checks go
red, the build does **not** fail: it re-runs the engine with "these checks failed: `<diagnostics>`; the
current diff is `<incl. any human commits>`", **pushes the fix additively** (never a blind force-push —
§2.5), and re-awaits checks. The loop is **bounded** (a small attempt cap); exhausting it fails the
build (mutex released, not wedged).

**Timers/signals replace the reconciler.** Where Flow's `Reconciler` was kicked every 3 min by Cloud
Scheduler and re-derived every live pipeline's state from GitHub, the build instead:
- prefers the **signal** (`onPrMerged` / `onCheckCompleted`) delivered by the api from a GitHub webhook
  — event-driven, no polling;
- falls back to a bounded **`Workflow.await(pollInterval) { … }`** that calls a GitHub read activity — a
  per-build backstop timer, not a global sweep. `Workflow.currentTimeMillis()` drives the
  no-runs-past-grace vacuous-green backstop Flow implemented with `noRunsGracePeriod`.

**Human-in-the-loop** is `Workflow.await { approved }` / the `abort` signal — durable waits that can sit
for days without a reconciler or a heartbeat, which is precisely what Temporal buys us.

**Dependent issues (B depends on A) fall out of the trunk-health gate — no special case.** The
trunk-health gate **generalizes and replaces** an earlier, narrower idea ("B's build awaits A's
`PostMergeWorkflow` reaching `DONE`"). "Trunk healthy" is the right predicate because it covers *all*
three ways a later build could land on a bad foundation at once: an explicit issue-dependency (B
depends on A), an *unrelated* stacked merge racing a prior post-merge, and a human breaking trunk by
hand. Concretely for B-depends-on-A: A merged → mutex released → the coordinator picks B, which
**starts its engine work in parallel** while A's `PostMergeWorkflow` watches trunk (performance); B then
**rebases onto the latest trunk** (A + any A-heal PRs — so trunk drift re-runs B's checks and routes any
breakage into B's pre-merge fix loop) and **gates its merge on `awaitTrunkHealthy()`** (correctness —
never land on a red/still-healing foundation). If A never heals (self-heal cap hit → escalated), B's
bounded gate escalates too rather than blocking forever. `input.dependsOnIssue` still exists to inform
*pick order*, but the merge-safety guarantee is the single trunk-health gate, not a per-dependency await.

### 2.3 `PostMergeWorkflow` — the detached half (deploy, heal, done)

```kotlin
@WorkflowInterface
interface PostMergeWorkflow {
  @WorkflowMethod fun run(input: PostMergeInput)
  @SignalMethod  fun approve()                       // human gate for a high-risk heal-PR merge
  @SignalMethod  fun onCheckCompleted(update: CheckUpdate)  // trunk workflow_run/check_run (§6.7)
  @QueryMethod   fun status(): PostMergeStatus
}
```

**Workflow id = `postmerge:<owner>/<name>#<issue>`.** Started with `ParentClosePolicy.ABANDON` by the
`BuildWorkflow` at merge, it runs **mutex-free** — the repo has already moved on to the next issue. It:

1. **Trigger-or-observe the deploy (§6.7).** `triggerDeploy` is mostly *observe*: a merge to trunk
   auto-fires the repo's `apply-*` GitHub Actions on `push`, so the workflow just awaits their result.
   It *dispatches* only for the deploys that need an explicit `workflow_dispatch`.
2. **Await the post-merge check** on the trunk commit, fed by GitHub `workflow_run`/`check_run` webhooks
   → api → `onCheckCompleted` (with the same backstop poll timer as §2.2).
3. **Green → `DONE`. Red → the post-merge heal track.**

`PostMergeWorkflow` is the **self-healer of Farm's own merges**, *not* the authority for trunk health.
The merge gate reads the literal branch state (§2.1–§2.2), which already reflects this merge's
check/deploy result via the api's trunk webhooks regardless of which workflow — if any — is watching.
So the post-merge workflow does not "report health up" as the gate's source of truth; it *consumes*
trunk state to decide whether **its own** merge needs healing. (Its trunk `check_run`/`workflow_run`
results reach a waiting build only as the low-latency `onTrunkStatusChanged` *hint* of §2.1 — never as an
authoritative relay; the gate re-reads `getTrunkHealth` regardless.)

**Self-heal scope (§6.6): the gate is universal, the self-heal is Farm-scoped by default.** The
trunk-health merge *gate* pauses merging for a red trunk from **any** cause. But Farm **self-heals only
the breakage of its own merges** — it does **not** auto-jump-in to fix a human's active out-of-band
change, because two actors editing trunk at once is a conflict, not a heal. For a human-caused red trunk
Farm therefore **holds (gate) and escalates**, rather than opening a competing fix PR. ("Farm heals any
red trunk" is a per-repo opt-in to add later.)

**Post-merge heal track (§6.6, distinct heal loop #2).** A merged commit **cannot be force-pushed**, so
healing trunk is *not* the pre-merge fix loop — it opens a **NEW PR against trunk** carrying the fix,
which itself flows through the normal build/merge machinery (green → merge re-fires the apply). This is
the README's "the loop a person runs — watch, read the error, open the fix — becomes the agent's own."
It is **bounded** by a self-heal attempt cap (2–3): exhausting it **escalates to a human** rather than
recursing forever. The heal track respects the same **escalation boundary** as any merge: a fix that
touches **operator-only root infra** is authored and **escalated**, not applied (§6.7).

### 2.4 Merge vs. ping-reviewer, and the concurrency summary

**Risk-gated auto-merge (§6.6).** Merging is neither always-ask nor never-ask:
- **Default: auto-merge when green.** Arm GitHub-native auto-merge; the PR lands the moment checks pass.
- **Require a human `approve` when the PR is high-risk.** The trigger is *"it touches infra that needs
  an apply, especially operator-only root infra"* (which dovetails with the escalation boundary in
  §6.7), plus a per-repo **"always review"** override for repos that want a human on every merge.

**Two task queues, one worker process (§6.2).** The 2-hour `runEngine` activity and the seconds-long
everything-else must not share a concurrency budget, or a burst of engine runs starves orchestration,
GitHub, and DB activities:

| Task queue | Registered on it | Concurrency knob |
|---|---|---|
| `farm-pipeline` | all three workflows + all **light** activities (Repo/GitHub/Deploy/DomainStore) | its own `maxConcurrentActivityExecutionSize`, generous |
| `farm-engine` | **only** `runEngine` | its own `maxConcurrentActivityExecutionSize`, small |

`runEngine` is pinned to the engine queue by `ActivityOptions.setTaskQueue("farm-engine")` on its stub;
everything else uses the default (`farm-pipeline`). Both workers share one `WorkerFactory` in one
process today — the split is a *concurrency* boundary now and a *relocation* seam later (§6.3).

| Flow mechanism | Farm on Temporal |
|---|---|
| `InMemoryRepoLock` (single-instance only) | `RepoCoordinatorWorkflow`, id = repo (native single-execution mutex) |
| `issue_pipelines` partial unique index (pick guard) | one **owned** `BuildWorkflow` child started per coordinator loop |
| mutex releases at merge (`blocksPick` excludes AwaitingMergeChecks) | coordinator **awaits an owned `BuildWorkflow`** that returns *at merge* — no release signal, no orphan (section B) |
| `FAILED` holds the mutex until a human clears it | failure **releases** the mutex by default; a per-repo **consecutive-failure circuit breaker** is the only pause (§6.6) |
| a red post-merge check wedges the repo (Flow) / nothing stops a merge onto red trunk | second gate: **`awaitTrunkHealthy()`** before merge, reading the **literal default-branch state** via `getTrunkHealth` (tri-state red/pending/green; flaky failures re-run first; `onTrunkStatusChanged` is only a latency hint); a red trunk from **any** author pauses the repo's merges as **backpressure**, bounded → escalate; admin **break-glass** override (audited, global scope time-boxed) is the recourse |
| worker-death requeue (`maxWorkerDeathRetries`, lazy heartbeat expiry) | activity **heartbeat timeout** + **retry policy** |
| reconciler poll every 3 min | signals (webhook→api) + per-workflow backstop timers |
| one worker/queue for everything | **`farm-pipeline` + `farm-engine`** split, so the engine can't starve orchestration |

### 2.5 Farm as a co-author — the reactor stance

Farm's pipeline is a **reactor to the real PR/GitHub state, never the assumer of its own last-known
state.** This is a stance, not a feature: it means "a human added a fixup to a Farm-owned PR," "a human
merged it," and "a human closed it" are **not special cases** — they are all just *"state changed;
react."* It is what makes Farm a collaborator you can jump in and help, rather than an automaton that
fights you for the branch.

Concretely:

- **Re-fetch before every write; no clobbering.** The pre-merge fix loop (§2.2) must **not** blindly
  `force-push` — a human may have pushed since the workflow last looked. It prefers an **additive
  commit**, and reserves force-push for **Farm-authored rebases that first reconcile/replay any human
  commits forward.** When it fixes, it feeds the engine the **current diff (including human commits)**
  plus the check failure — not the diff Farm remembers producing.
- **Every transition is event-driven and idempotent, keyed on who *actually* acted.** `onPrMerged`
  already fires regardless of who merged (Farm's auto-merge, a human, or GitHub). We add the two
  missing external-event signals so the build is never surprised:
  - **`onPrClosed`** — a human abandoned the PR; the build stops cleanly (fails/returns) instead of
    fighting to re-open or re-push.
  - **`onBranchUpdated`** — a human pushed to the branch; the build re-fetches and folds the human's
    work in rather than clobbering it.
  No transition assumes "I opened this PR, therefore it is untouched."

---

## 3. Temporal activity model

All side effects are `@ActivityInterface`s (see
[`Activities.kt`](src/main/kotlin/software/medusa/farm/worker/activity/Activities.kt)). Activities are
where non-determinism lives; the workflows only call stubs. This is also the axis of the versioning
policy (§6.5): **workflows stay thin and stable; the churn-prone logic lives in activities, which may
change freely.**

| Activity | Methods | Task queue | Retry / timeout | Idempotency |
|---|---|---|---|---|
| `RepoActivities` | `prepareWorkspace`, `publishBranchAndOpenPr`, `pushFix`, `rebaseOntoTrunk`, `cleanupWorkspace` | `farm-pipeline` | short `startToClose` (5 min), retry ≤5 | keyed on `(repo, issue)`; branch `farm/issue-<n>`; `publish` returns `hadChanges=false` on empty diff; `pushFix` is **additive** (§2.5) |
| `EngineActivities` | `runEngine` | **`farm-engine`** | **long** `startToClose` (2 h), **short** `heartbeatTimeout` (2 min), retry ≤2 | resume via `resumeSessionId`; see below |
| `GitHubActivities` | `discoverNextReadyIssue`, `getPullRequestState`, `getMergeCheckStatus`, `getTrunkHealth`, `armAutoMerge`, `markIssueDone`, `setPipelineLabel` | `farm-pipeline` | short, retry ≤5 | reads are naturally idempotent; `getTrunkHealth` returns tri-state (red/pending/green) off the tip commit's runs, re-running a flaky failure before calling it red (§2.2); `armAutoMerge`/`markIssueDone` are PUT/close (idempotent) |
| `DeployActivities` | `triggerDeploy`, `getDeployStatus` | `farm-pipeline` | short, retry ≤5 | `triggerDeploy` is **trigger-or-observe** and idempotent per merge sha (§6.7) |
| `DomainStoreActivities` | `upsertPipeline`, `recordStageTransition`, `recordSession` | `farm-pipeline` | short, retry ≤5 | upserts keyed on `(repo, issue[, stage])` |

### The engine activity — **heartbeating, `claude`-only to start**

`runEngine` wraps Flow's `HrsTaskCompleter.completeTask(...)` — a subprocess run of the **pinned engine
CLI** (`stream-json`), with the health-gate bounce loop. It is a **heartbeating** activity (§6.3):

- The engine subprocess is owned by **this same worker process**. Async (manual) completion is for when
  a *different* system finishes the work later (its value is releasing the worker slot); here the worker
  is doing the work, so there is nothing to hand off. Heartbeating is the right tool: it gives Temporal
  a liveness signal (a dead worker is detected via `heartbeatTimeout` and the activity is retried on
  another worker — replacing Flow's lazy heartbeat-expiry + `maxWorkerDeathRetries`), and it is the
  **cancellation channel** — Flow overloaded its `heartbeat()` gRPC return value as the abort signal; on
  Temporal, the `abort` workflow signal cancels the activity and the heartbeat observes cancellation.
  Progress (`observeAgentAction`, `observeRunCost`) rides in heartbeat details and is recovered on retry
  via `getHeartbeatDetails`.
- **Ship `claude` only, to start.** Farm is a clean slate; it does **not** inherit the engines that
  caused Flow trouble. `builtin` is dropped and `leader` is deferred — `FARM_ENGINE` selects among what
  ships, which is `claude` today. The **pinned engine CLI is baked into the worker image** and **rolled
  with the image** (no runtime download of a binary), so the engine version is a property of the deploy.
- **When to switch to async completion:** if the engine ever moves off the worker onto a separate
  (`farm-engine`) fleet, `runEngine` becomes an async-completion activity (`doNotCompleteOnReturn()` +
  a taskToken the fleet completes via `ActivityCompletionClient`). The interface and the `farm-engine`
  task-queue boundary are unchanged — that boundary *is* the seam. Documented for the operator.

Operational crashes (bad auth, cap, subprocess crash) are **thrown** so Temporal's retry policy handles
them; deterministic verdicts (success / no-changes / gave-up) are **returned** as `EngineOutcome` so the
workflow decides. This preserves Flow's crucial distinction between *worker death* (retry) and *genuine
engine failure* (terminate).

### GitHub App credentials — ported verbatim, held at the worker singleton (§6.4)

Farm **ports Flow's `github-app-auth` verbatim** — `GitHubAppTokenMinter` and
`RefreshingGitHubAppToken`. Credentials come from **Secret Manager → env**: `FARM_GITHUB_APP_ID` and the
PKCS#8 `FARM_GITHUB_APP_PEM`. The one refinement over a naive port: the `RefreshingGitHubAppToken` is
held at the **activity-impl (worker-singleton) level** — minted **once** when the activity impls are
constructed, and **auto-refreshed across activity calls** — not re-minted per activity invocation. (This
App-token minting was never the problematic path; it is distinct from the worker-identity gRPC token.
Only the App token is discussed here.)

---

## 4. DB architecture — Temporal owns orchestration, Postgres owns the domain

**Split of responsibility.** Temporal's event history owns everything in-flight: current stage, pending
timers, retry counts, signal state, human-in-the-loop waits. Postgres is **not** the state machine any
more — it is the durable **domain store + read model** that the web console and `ms-farm` query, and the
sink for artifacts too big or too permanent for workflow history (engine transcripts, costs, results).
Every DB write is an **activity side effect** (`DomainStoreActivities`), so it inherits Temporal's
at-least-once delivery + retries; writes are idempotent upserts.

This split is also why the **14-day namespace retention (§6.1) is only a debugging window**, not a data-
loss risk: **the DB read model is the system of record for history.** Temporal history for a closed
workflow is a convenience for post-mortem replay; once it ages out, "what happened" still lives in
`pipeline` / `stage_transition` / `session`.

Wiring mirrors `backend/api`'s `PostgresCounterStore` exactly (Hikari + explicit `org.postgresql.Driver`
+ Flyway migrate on boot + SQLDelight generated queries; Flyway owns the runtime schema, the `.sq`
`CREATE TABLE`s exist only for compile-time query verification). SQLDelight database
`FarmWorkerDatabase`, package `software.medusa.farm.worker.db`.

Schema sketch (`V1__create_worker_schema.sql` + `Pipeline.sq` / `Session.sq`):

```sql
-- read model of live + historical pipelines (projected from workflow transitions)
pipeline(repo_full_name, issue_number, stage, pr_number, pr_url,
         merge_commit_sha, failure_summary, updated_at,  PK(repo_full_name, issue_number))

-- append-only audit of stage changes (timeline for the console)
stage_transition(id BIGSERIAL, repo_full_name, issue_number, stage, at)

-- one row per engine run: transcript pointer, cost, outcome
session(id, repo_full_name, issue_number, engine_session_id, outcome_kind,
        transcript_uri, total_cost_usd, created_at)
```

Future tables (not in the scaffold migration): `repo` (installation id, settings incl. the per-repo
"always review" override, §2.4), `deploy` (per-merge apply status), `artifact` (blob pointers). Large
transcripts live in object storage; `transcript_uri` points at them.

### Read path — split, deliberately

Recommend a **split** between the DB read model and Temporal queries:

- **DB read model** (via `backend/api`) for **lists, history, and anything durable/cross-workflow**:
  "all pipelines for repo X", "the timeline of issue #42", "last night's cost" — fast, indexable,
  survives workflow completion/archival and the 14-day retention. This is the default the console and
  `ms-farm` hit.
- **Temporal `@QueryMethod`** (via `backend/api` holding a `WorkflowClient`) for **authoritative live
  state of a single in-flight workflow** — "what exactly is build #42 doing *right now*" — with no
  projection lag. Use sparingly; it only works while the workflow is open.

Rule of thumb: **the DB is the system of record for *what happened*; Temporal is the system of record
for *what is happening*.**

---

## 5. How `ms-farm` (admin CLI) + the web console fit

Both are clients of **`backend/api`**; neither touches Temporal or the worker directly.

```
ms-farm (CLI) ─┐
               ├─gRPC→ backend/api ──WorkflowClient──▶ Temporal namespace `farm`
web console  ──┘        │           │                     ├─ RepoCoordinatorWorkflow   (mutex; awaits…)
                        │           │                     │     │   ▵ onTrunkStatusChanged (latency hint only)
                        │           │                     │     └─ BuildWorkflow        (mutex-held; → merge)
                        │           │                     │            │ awaitTrunkHealthy() = getTrunkHealth (tip runs)
                        │           │                     │            └╌ PostMergeWorkflow (ABANDON; self-heals own merge)
   trunk check/deploy ──┘           │                     │
   webhooks (any author)            └──SQLDelight─────▶ Postgres read model (lists/history)
                                                        ▲
backend/worker ──activities (side effects: DB writes)──┘   (arg-less; only runs workflows)
       │  farm-pipeline: workflows + light activities
       └  farm-engine:   runEngine only (2h heartbeating subprocess)
```

- **Reads**: api serves lists/history from the DB read model; live single-workflow status via a Temporal
  query against the relevant `RepoCoordinator`/`Build`/`PostMerge` execution.
- **Writes/actions** (approve, abort, retry, re-pick): api translates the request into a **signal** to
  the relevant workflow (`BuildWorkflow.approve/abort`, `PostMergeWorkflow.approve`,
  `RepoCoordinatorWorkflow.approve/onReadyIssuesChanged`) or a `WorkflowClient.start` for a new
  coordinator. GitHub webhooks land on api and are forwarded as signals — `onPrMerged`,
  `onCheckCompleted`, `onPrClosed`, `onBranchUpdated`, and trunk `workflow_run`/`check_run` results as
  `onTrunkStatusChanged` to the coordinator — replacing Flow's `GitHubWebhookService` → detached
  reconcile.
- The **worker stays arg-less** and never exposes an API; it only registers and runs implementations.
  `ms-farm` is administration, not orchestration.

---

## 6. Decisions (resolving the first draft's open questions)

The first draft closed with seven open questions. They are now **decided**; each subsection records the
decision and where it lands in the design. A short list of **genuinely-remaining** open items follows.

### 6.1 Namespace — `farm` + `farm-staging`, Terraform-created, 14-day debug window
Two namespaces, **`farm`** and **`farm-staging`**. They are created **declaratively by
temporal-instance issue #8's Terraform consumption module** — *not* by a manual
`temporal operator namespace create`, so namespace existence and retention are codified and
reproducible. **Retention: 14 days**, framed strictly as a **debugging window** (post-mortem replay of
closed workflows), not as history storage — the **DB read model is the system of record for history**
(§4), so the retention is freely adjustable without data loss. **Farm depends on #8** delivering these
namespaces before it can run in an environment.

### 6.2 Task-queue partitioning — SPLIT, now
**Two task queues in one worker process**: `farm-pipeline` (workflow tasks + all light activities) and
`farm-engine` (**only** `runEngine`), each with its own `maxConcurrentActivityExecutionSize`, both
registered on the same `WorkerFactory` (§1, §2.4). Rationale: a 2-hour engine activity next to
seconds-long orchestration/GitHub/DB activities means engine saturation would starve everything else
unless they have independent concurrency budgets. Splitting now also keeps the exact **seam to relocate
the engine worker to a separate fleet later** (§6.3). `runEngine` carries
`ActivityOptions.setTaskQueue("farm-engine")`.

### 6.3 Engine invocation — in-process subprocess + heartbeating; `claude` only
Confirmed: the engine runs as an **in-process subprocess** driven by a **heartbeating** activity (the
§3 argument stands). **Ship `claude` only to start** — drop `builtin`, defer `leader`; Farm is a clean
slate and does not inherit the engines that caused trouble. The **pinned engine CLI is baked into the
worker image and rolled with the image**, selected by `FARM_ENGINE`. **Async-completion remains the
documented swap-in** for a future separate engine fleet, along the `farm-engine` task-queue seam.

### 6.4 GitHub App creds — port `github-app-auth`, hold the token at the worker singleton
**Port Flow's `github-app-auth` verbatim** (`GitHubAppTokenMinter` / `RefreshingGitHubAppToken`).
Credentials flow **Secret Manager → env** (`FARM_GITHUB_APP_ID` + PKCS#8 `FARM_GITHUB_APP_PEM`).
Refinement: hold the `RefreshingGitHubAppToken` at the **activity-impl (worker-singleton) level** —
minted once, auto-refreshed across activity calls (§3). This is distinct from the worker-identity gRPC
token; the App-token minting itself was never problematic.

### 6.5 Workflow versioning — thin workflows / fat activities / drain-don't-patch
Only **workflow** code is determinism-constrained on replay; **activities change freely.** So keep the
three workflows as **stable orchestration skeletons** and push churn-prone logic into **activities**
(§3). For the workflow changes that do happen, **prefer drain** (a Build-ID / new-task-queue drain of
in-flight histories) over **`Workflow.getVersion` patching** — `getVersion` is the rare escape hatch;
its guards rot and are easy to forget. The coordinator's `continueAsNew` (between picks) gives us **free
version boundaries** for the common case. **OPEN:** confirm whether the self-hosted Temporal has **Worker
Versioning (Build IDs)** enabled (to be checked as part of temporal-instance #8/#9); if not, workflow
changes are a **manual task-queue drain**.

### 6.6 Failure / heal / merge model — release the mutex, two heal loops, risk-gated merge
- **Release the mutex on failure by default — never routinely wedge.** Flow's "FAILED holds the mutex
  until a human clears it" was the recurring operational tax; Farm drops it. Two bounds keep this safe: a
  **per-repo consecutive-failure circuit breaker** (after K straight `BuildWorkflow` failures → pause
  re-pick, require a human `approve` — a *soft* escalation, §2.1), and a **self-heal attempt cap** (2–3 →
  escalate to a human instead of recursing, §2.3).
- **Two DISTINCT heal loops.** *Pre-merge fix loop* (§2.2): checks red **before** merge → engine fixes →
  push the branch (additively) → re-await checks → repeat, bounded (`stage < MERGED`). *Post-merge heal
  track* (§2.3): trunk red **after** merge → a **new PR** against trunk (merged commits can't be
  force-pushed), bounded (`stage ≥ MERGED`; this is the detached `PostMergeWorkflow`).
- **Merge vs. ping-reviewer = risk-gated auto-merge** (§2.4). Default **auto-merge when green**; require
  the human `approve` when the PR is **high-risk** — "it touches infra that needs an apply, especially
  operator-only root infra" (dovetails with §6.7) — plus a per-repo **"always review"** override. Not
  always-ask, not never-ask.
- **The trunk-health merge gate — a second gate, distinct from the mutex** (§2.1–§2.4). The mutex
  serializes *build starts*; it does **not** stop a build that ran in parallel from merging onto a trunk
  that just went red. So a build, after green pre-merge checks and a rebase onto latest trunk, must clear
  **`awaitTrunkHealthy()`** before merging. **Single source of truth = `getTrunkHealth`** — the literal
  default-branch state, *not* Farm's own `PostMergeWorkflow`s (else a human's out-of-band merge that broke
  trunk would be invisible); `onTrunkStatusChanged` webhooks are **only a latency hint**, never a cached
  authoritative relay. Because the build holds the mutex while it waits, a red trunk **pauses the whole
  repo's merges** as natural backpressure. **Bounded → escalate** if trunk stays red past the bound.
- **"Red trunk" is defined mechanically, and health is tri-state** (§2.2). A relevant run is any run with
  `head_branch == default_branch` **and** `head_sha == the trunk tip` — **opt-out** (everything that ran
  against the tip counts; exclude a noisy workflow individually), never an opt-in declared set; this
  auto-excludes PR checks (other branch) and stale runs (other commit). **red** = ≥1 tip-linked run
  concluded in a **real** failure; **pending** = a tip-linked run still running → the gate **waits** (the
  normal state just after a merge); **green** = all concluded, none failed (no relevant runs = vacuously
  green).
- **Flaky failures don't count as red** (§2.2). A concluded failure is red **only after re-run
  discrimination** — a single flaky/transient run (GCS-503 / Actions-outage / flaky-test class) is
  re-run first and is not red on its own. This is the mechanism that stops the gate from **wedging the
  repo on noise**.
- **Break-glass override — the gate is a safe default, not a one-way door** (§2.2). An admin overrides via
  the **same signal path as `approve`/`abort`** (ms-farm/console → api → signal) at three scopes —
  **per-PR** (land this one anyway), **per-repo** (gating off for this repo), **global-temporary** (off
  until I say). Two guardrails: every override is **audited** (who/when/why), and the **global scope is
  time-boxed** (auto-expires). It is the human recourse for the gate's false-positives.
- **Self-heal scope for a human-caused red trunk: the gate is universal, the self-heal is Farm-scoped.**
  The merge *gate* pauses for a red trunk from **any** cause. But Farm **self-heals only its own merges'
  breakage** — it does **not** auto-open a competing fix PR against a human's active out-of-band change
  (two actors editing trunk is a conflict, not a heal); it **holds and escalates** instead. "Farm heals
  any red trunk" is a per-repo opt-in to add later.
- **Dependent issues (B depends on A): split "may START" from "may MERGE," now via trunk-health** (§2.2).
  A *merged* → mutex releases → **B may START** its engine work in parallel while A watches post-merge
  (performance). B **may MERGE** only when trunk is healthy — which for a B-on-A dependency means A's
  post-merge went green. **Rebase is the reconciler**: B rebases onto the latest trunk (A + any A-heal
  PRs); its checks re-run and catch trunk drift, which routes into B's pre-merge fix loop. This is the
  same **`awaitTrunkHealthy()`** gate as above — it **generalizes and replaces** the earlier
  "B awaits A's `PostMergeWorkflow` DONE" formulation, and additionally covers unrelated stacked merges
  and a human breaking trunk. Edge cases: if A never heals (self-heal cap hit → escalated), B's gate is
  likewise **capped → escalate**; and the chosen default is **start-in-parallel then gate-the-merge**
  (vs. the simpler gate-the-start).

### 6.7 Deploy surface — CI-scoped self-heal, escalate operator-only root infra
`triggerDeploy` is **trigger-or-observe**: a merge to trunk **auto-fires the repo's `apply-*` GitHub
Actions on `push`**, so it is mostly *observe*; it *dispatches* only for deploys needing an explicit
`workflow_dispatch`. `onCheckCompleted` is fed by GitHub `workflow_run`/`check_run` webhooks on the
trunk commit → `api` → signal. **The boundary decision: Farm stays CI-scoped and ESCALATES.** Self-heal
**fully closes the loop for CI-applied failures** (Cloud Run / app infra: Farm opens a fixing PR, the
merge re-fires the apply). For **operator-only root infra** — IAM grants, Shared VPC, e.g.
`artifactregistry.admin` / `iap.tunnelResourceAccessor` — Farm does **not** hold elevated cloud creds;
it **authors the fix PR/issue and escalates to the operator**, who applies it. Granting Farm elevated
creds is revisited only once self-heal is trusted on the CI-applied class. This is the decided default.

### Remaining open items
- **Worker Versioning (Build IDs)** enabled on the self-hosted Temporal? (blocks the "drain, don't
  patch" mechanism in §6.5; check with temporal-instance #8/#9 — else manual task-queue drain).
- **Numeric thresholds, tune operationally** (§6.6): the circuit-breaker `K` (≈3) and self-heal cap
  (2–3); the trunk-health-gate wait bound; the flaky re-run count (how many transient failures before a
  concluded failure is called *real* red); and the global break-glass time-box duration. The *policy* is
  decided; only the constants are pending.
- **Concrete `apply-*` workflow names / dispatch inputs** per repo (§6.7) — enumerated as repos onboard.
