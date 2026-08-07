# `backend/worker` — the Farm worker, on Temporal

> Status: design + compiling scaffold. Workflow/activity **interfaces** and the process bootstrap are
> real and compile; activity **bodies** and a few workflow waits are stubbed (`TODO`). This document
> is the authoritative spec; the Kotlin under `src/main` is the shape it lands in.

This module is Farm's autonomous-coding-agent worker — Flow's `worker/` **re-expressed on Temporal**.
Flow modelled the `issue → PR → merge → deploy` lifecycle as an ad-hoc state machine stitched from a
Postgres schema plus an externally-triggered reconciler loop, with the per-repo mutex enforced by
partial unique indexes and an in-process lock. Farm hands that orchestration to Temporal: retries,
timeouts, human-in-the-loop waits, the per-repo mutex, and long-running coordination become the
platform's job. The DB stops being the state machine and becomes the **domain store + read model**.

It also closes the gap the README names: Flow stops at "issue closed" and never converges the running
system onto a merge, and a red post-merge check wedges the repo until a human clears it. Farm's
pipeline continues **past merge** into deploy/apply and, on failure, **self-heals** by treating the
broken trunk as a fresh task.

---

## 0. Process shape — arg-less worker, admin-only CLI

Three processes, kept crisply separate:

| Process | What it is | How it starts |
|---|---|---|
| **`backend/worker`** (this module) | The long-running Temporal worker. Registers workflow + activity impls on a task queue and runs them. | **Arg-less, env-configured.** `main()` reads env → connects to Temporal → `factory.start()`. Container-style, like Flow's worker image. |
| **`backend/api`** | Armeria gRPC service (exists today as the counter demo). The **only** thing that *starts/steers* workflows: it holds a `WorkflowClient`, and signals/queries workflows + reads the DB read model on behalf of clients. | Its own deployable (`backend/api/gcp`). |
| **`ms-farm`** (`cli/`) | Admin helper — a web-console alternative for administration. Talks to `backend/api` over gRPC. **Never** runs workflows and is **not** the worker entrypoint. | User-invoked CLI. |

The worker takes **no arguments**. Contrast Flow, whose worker was the `flow work` CLI subcommand
(`WorkCommand`). Everything that was a CLI flag or `WrkConfig` field is now an env var read by
[`WorkerConfig.fromEnvironment`](src/main/kotlin/software/medusa/farm/worker/WorkerConfig.kt).

```
TEMPORAL_ADDRESS      (default 127.0.0.1:7233)   # org demo convention
TEMPORAL_NAMESPACE    (default farm)
FARM_WORKER_TASK_QUEUE(default farm-pipeline)
DATABASE_URL          (required)                 # full JDBC URL incl. creds + sslmode
FARM_GITHUB_APP_ID    (required)
FARM_GITHUB_APP_PEM   (required)                 # PKCS#8 GitHub App private key
FARM_ENGINE           (default claude)
FARM_ENGINE_API_KEY   (optional)
FARM_ORG_OWNER        (required)                 # fences all GitHub ops to one org
```

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
    TaskQueues.kt             shared task-queue name const(s)
    TemporalWorkerHost.kt     stubs → client → WorkerFactory → register → start → shutdown hook
    model/Model.kt            payload data classes + enums (workflow/activity/signal/query IO)
    workflow/
      Workflows.kt                 @WorkflowInterface RepoCoordinatorWorkflow, PipelineWorkflow
      RepoCoordinatorWorkflowImpl  per-repo mutex holder
      PipelineWorkflowImpl         per-issue lifecycle orchestrator
    activity/
      Activities.kt                @ActivityInterface Repo/Engine/GitHub/Deploy/DomainStore
      impl/ActivityImpls.kt        stub impls (TODO bodies)
      impl/WorkerDomainStore.kt    Hikari + Flyway + SQLDelight (mirrors PostgresCounterStore)
  src/main/sqldelight/software/medusa/farm/worker/db/
    Pipeline.sq  Session.sq        compile-time schema + queries (FarmWorkerDatabase)
  src/main/resources/db/migration/
    V1__create_worker_schema.sql   Flyway-owned runtime schema (mirrors the .sq CREATE TABLEs)
  src/test/kotlin/.../WorkerConfigTest.kt
```

**Bootstrap** ([`TemporalWorkerHost`](src/main/kotlin/software/medusa/farm/worker/TemporalWorkerHost.kt)):
`WorkflowServiceStubs.newServiceStubs(target = TEMPORAL_ADDRESS)` → `WorkflowClient.newInstance(namespace = TEMPORAL_NAMESPACE)`
→ `WorkerFactory.newInstance(client)` → `factory.newWorker(taskQueue)` →
`registerWorkflowImplementationTypes(RepoCoordinatorWorkflowImpl, PipelineWorkflowImpl)` +
`registerActivitiesImplementations(...)` → `factory.start()`, with a JVM shutdown hook calling
`factory.shutdown()` + `service.shutdown()` for graceful drain. This mirrors the org's Go
hello-workflow one-for-one (env-driven address+namespace, one shared task-queue const), on the JVM SDK.
The frontend is **plaintext, VPC-private** (no TLS/auth), so the worker must run inside the same VPC.

---

## 2. Temporal workflow model

Flow had **two** state machines — the issue-pipeline (`InProgress → PrOpen → AwaitingMergeChecks →
Done | Failed`) and the session (`Pending → Running → Completed | Failed | Aborted`) — plus a
reconciler that polled GitHub to advance them. Farm collapses both into **one durable workflow per
issue** and hands the "poll and advance" job to Temporal timers + signals.

### 2.1 `RepoCoordinatorWorkflow` — the per-repo mutex, natively

```kotlin
@WorkflowInterface
interface RepoCoordinatorWorkflow {
  @WorkflowMethod fun coordinate(repo: RepoRef)
  @SignalMethod  fun onReadyIssuesChanged()            // api nudge (webhook: new flow:ready issue)
  @SignalMethod  fun onPipelineMutexReleased(issueNumber: Int)  // a child reached MERGED
  @QueryMethod   fun status(): RepoCoordinatorStatus
}
```

**Workflow id = `repo:<owner>/<name>`.** Temporal guarantees a single running execution per workflow
id, so *this is the mutex* — no partial unique index, no in-process `RepoLock`, no advisory-lock TODO
for multi-instance (the exact gap Flow's `InMemoryRepoLock` left open). The coordinator loops:
discover the next unblocked `flow:ready` issue → start **one** child `PipelineWorkflow` → block on
`Workflow.await { mutexReleasedFor == issue }` → pick the next. When idle it parks on
`Workflow.await(idlePollInterval) { wakeUp }`, woken by `onReadyIssuesChanged` or the timer.
`Workflow.continueAsNew` bounds history after N pipelines.

Crucially the mutex **releases at MERGED, not DONE** (matching Flow's `blocksPick` semantics, where
`AwaitingMergeChecks` did not block): the child is started with **`ParentClosePolicy.ABANDON`** and
signals the coordinator the moment it merges, so the next issue branches off updated trunk while the
child keeps watching post-merge checks and self-heals independently.

### 2.2 `PipelineWorkflow` — the per-issue lifecycle

```kotlin
@WorkflowInterface
interface PipelineWorkflow {
  @WorkflowMethod fun run(input: PipelineInput): PipelineResult
  @SignalMethod  fun approve()                         // human-in-the-loop gate
  @SignalMethod  fun abort(reason: String)             // cancels the in-flight engine activity
  @SignalMethod  fun onCheckCompleted(update: CheckUpdate)  // webhook → api → signal (no busy poll)
  @SignalMethod  fun onPrMerged(mergeCommitSha: String)
  @QueryMethod   fun status(): PipelineStatus
}
```

**Workflow id = `pipeline:<owner>/<name>#<issue>`.** One durable `PipelineStage` enum replaces both of
Flow's machines:

```
PREPARING → ENGINE_RUNNING → PR_OPEN → AWAITING_MERGE_CHECKS → MERGED
          → POST_MERGE_CHECKS → (SELF_HEALING?) → DONE
   any stage → FAILED
```

`run()` is straight-line orchestration (see
[`PipelineWorkflowImpl`](src/main/kotlin/software/medusa/farm/worker/workflow/PipelineWorkflowImpl.kt)):
`prepareWorkspace` → `runEngine` → `publishBranchAndOpenPr` → `armAutoMerge` → **await merge** →
notify coordinator (mutex release) → `triggerDeploy` → **await post-merge checks** → self-heal on red
→ `markIssueDone`.

**Timers/signals replace the reconciler.** Where Flow's `Reconciler` was kicked every 3 min by Cloud
Scheduler and re-derived every live pipeline's state from GitHub, each Farm pipeline instead:
- prefers the **signal** (`onPrMerged` / `onCheckCompleted`) delivered by the api from a GitHub
  webhook — event-driven, no polling;
- falls back to a bounded **`Workflow.await(pollInterval) { … }`** that calls a GitHub read activity —
  a per-pipeline backstop timer, not a global sweep. `Workflow.currentTimeMillis()` drives the
  no-runs-past-grace vacuous-green backstop Flow implemented with `noRunsGracePeriod`.

**Human-in-the-loop** is `Workflow.await { approved }` / the `abort` signal — durable waits that can
sit for days without a reconciler or a heartbeat, which is precisely what Temporal buys us.

### 2.3 Self-heal (child workflow)

On a red `POST_MERGE_CHECKS`, `selfHeal()` treats the broken trunk as a fresh task: it starts a
**child `PipelineWorkflow`** (its own id, e.g. `pipeline:<repo>#heal-<sha>`) whose `PipelineInput`
carries `healingForMergeSha`. The engine activity is told "trunk apply failed with <diagnostics>, fix
it". This is the README's "the loop a person runs — watch, read the error, open the fix — becomes the
agent's own", expressed as workflow recursion. (Scaffold: `selfHeal` logs + `TODO`; the child-start is
sketched.)

### 2.4 Concurrency summary

| Flow mechanism | Farm on Temporal |
|---|---|
| `InMemoryRepoLock` (single-instance only) | `RepoCoordinatorWorkflow`, id = repo (native single-execution mutex) |
| `issue_pipelines` partial unique index (pick guard) | one child pipeline started per coordinator loop |
| mutex releases at merge (`blocksPick` excludes AwaitingMergeChecks) | `ABANDON` child + `onPipelineMutexReleased` at MERGED |
| worker-death requeue (`maxWorkerDeathRetries`, lazy heartbeat expiry) | activity **heartbeat timeout** + **retry policy** |
| reconciler poll every 3 min | signals (webhook→api) + per-pipeline backstop timers |

---

## 3. Temporal activity model

All side effects are `@ActivityInterface`s (see
[`Activities.kt`](src/main/kotlin/software/medusa/farm/worker/activity/Activities.kt)). Activities are
where non-determinism lives; the workflow only calls stubs.

| Activity | Methods | Retry / timeout | Idempotency |
|---|---|---|---|
| `RepoActivities` | `prepareWorkspace`, `publishBranchAndOpenPr`, `cleanupWorkspace` | short `startToClose` (5 min), retry ≤5 | keyed on `(repo, issue)`; branch `farm/issue-<n>-<engine>`; `publish` returns `hadChanges=false` on empty diff |
| `EngineActivities` | `runEngine` | **long** `startToClose` (2 h), **short** `heartbeatTimeout` (2 min), retry ≤2 | resume via `resumeSessionId`; see below |
| `GitHubActivities` | `discoverNextReadyIssue`, `getPullRequestState`, `getMergeCheckStatus`, `armAutoMerge`, `markIssueDone`, `setPipelineLabel` | short, retry ≤5 | reads are naturally idempotent; `armAutoMerge`/`markIssueDone` are PUT/close (idempotent) |
| `DeployActivities` | `triggerDeploy`, `getDeployStatus` | short, retry ≤5 | `triggerDeploy` idempotent per merge sha |
| `DomainStoreActivities` | `upsertPipeline`, `recordStageTransition`, `recordSession` | short, retry ≤5 | upserts keyed on `(repo, issue[, stage])` |

### The engine activity — **heartbeating, not async-completion**

`runEngine` wraps Flow's `HrsTaskCompleter.completeTask(...)` — a subprocess run of the pinned engine
CLI (`stream-json`), with the health-gate bounce loop. It is a **heartbeating** activity:

- The engine subprocess is owned by **this same worker process**. Async (manual) completion is for
  when a *different* system finishes the work later (its value is releasing the worker slot); here the
  worker is doing the work, so there is nothing to hand off. Heartbeating is the right tool: it gives
  Temporal a liveness signal (a dead worker is detected via `heartbeatTimeout` and the activity is
  retried on another worker — replacing Flow's lazy heartbeat-expiry + `maxWorkerDeathRetries`), and
  it is the **cancellation channel** — Flow overloaded its `heartbeat()` gRPC return value as the
  abort signal; on Temporal, the `abort` workflow signal cancels the activity and the heartbeat
  observes cancellation. Progress (`observeAgentAction`, `observeRunCost`) rides in heartbeat details
  and is recovered on retry via `getHeartbeatDetails`.
- **When to switch to async completion:** if the engine ever moves off the worker onto a separate GPU
  fleet, `runEngine` becomes an async-completion activity (`doNotCompleteOnReturn()` + a taskToken the
  fleet completes via `ActivityCompletionClient`). Interface unchanged; noted for the operator.

Operational crashes (bad auth, cap, subprocess crash) are **thrown** so Temporal's retry policy
handles them; deterministic verdicts (success / no-changes / gave-up) are **returned** as
`EngineOutcome` so the workflow decides. This preserves Flow's crucial distinction between *worker
death* (retry) and *genuine engine failure* (terminate).

---

## 4. DB architecture — Temporal owns orchestration, Postgres owns the domain

**Split of responsibility.** Temporal's event history owns everything in-flight: current stage,
pending timers, retry counts, signal state, human-in-the-loop waits. Postgres is **not** the state
machine any more — it is the durable **domain store + read model** that the web console and `ms-farm`
query, and the sink for artifacts too big or too permanent for workflow history (engine transcripts,
costs, results). Every DB write is an **activity side effect** (`DomainStoreActivities`), so it inherits
Temporal's at-least-once delivery + retries; writes are idempotent upserts.

Wiring mirrors `backend/api`'s `PostgresCounterStore` exactly (Hikari + explicit
`org.postgresql.Driver` + Flyway migrate on boot + SQLDelight generated queries; Flyway owns the
runtime schema, the `.sq` `CREATE TABLE`s exist only for compile-time query verification). SQLDelight
database `FarmWorkerDatabase`, package `software.medusa.farm.worker.db`.

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

Future tables (not in the scaffold migration): `repo` (installation id, settings), `deploy`
(per-merge apply status), `artifact` (blob pointers). Large transcripts live in object storage;
`transcript_uri` points at them.

### Read path — split, deliberately

Recommend a **split** between the DB read model and Temporal queries:

- **DB read model** (via `backend/api`) for **lists, history, and anything durable/cross-workflow**:
  "all pipelines for repo X", "the timeline of issue #42", "last night's cost" — fast, indexable,
  survives workflow completion/archival. This is the default the console and `ms-farm` hit.
- **Temporal `@QueryMethod`** (via `backend/api` holding a `WorkflowClient`) for **authoritative live
  state of a single in-flight workflow** — "what exactly is pipeline #42 doing *right now*" — with no
  projection lag. Use sparingly; it only works while the workflow is open.

Rule of thumb: **the DB is the system of record for *what happened*; Temporal is the system of record
for *what is happening*.**

---

## 5. How `ms-farm` (admin CLI) + the web console fit

Both are clients of **`backend/api`**; neither touches Temporal or the worker directly.

```
ms-farm (CLI) ─┐
               ├─gRPC→ backend/api ──WorkflowClient──▶ Temporal (signal/query workflows)
web console  ──┘                   └──SQLDelight─────▶ Postgres read model (lists/history)
                                                        ▲
backend/worker ──activities (side effects: DB writes)──┘   (arg-less; only runs workflows)
```

- **Reads**: api serves lists/history from the DB read model; live single-workflow status via a
  Temporal query.
- **Writes/actions** (approve, abort, retry, re-pick): api translates the request into a **signal** to
  the relevant workflow (`PipelineWorkflow.approve/abort`, `RepoCoordinatorWorkflow.onReadyIssuesChanged`)
  or a `WorkflowClient.start` for a new coordinator. GitHub webhooks land on api and are forwarded as
  signals — replacing Flow's `GitHubWebhookService` → detached reconcile.
- The **worker stays arg-less** and never exposes an API; it only registers and runs implementations.
  `ms-farm` is administration, not orchestration.

---

## 6. Open questions / decisions for the operator

1. **Namespace naming.** Namespaces are not auto-created; the org demo creates its own (`demo`) with a
   retention. Proposed `farm` (+ `farm-staging`). Who runs
   `temporal operator namespace create --retention <dur> farm`, and what retention (affects how long
   closed pipelines are queryable before you must rely on the DB read model)?
2. **Task-queue partitioning.** One `farm-pipeline` queue is the default. Should the long `runEngine`
   activity get its own `farm-engine` queue + worker pool, so a saturated engine can't starve
   lightweight orchestration/GitHub activities? (Trivial to split: register the engine activity on a
   second worker; `ActivityOptions.setTaskQueue`.)
3. **Engine invocation.** Confirm the engine stays an in-process subprocess (heartbeating activity) vs.
   moving to a separate fleet (async-completion). Which engines ship first (`claude` default; is
   `leader`/`builtin` in scope)? Where does the pinned engine binary come from in the worker image?
4. **GitHub App creds.** `FARM_GITHUB_APP_ID` + `FARM_GITHUB_APP_PEM` (PKCS#8) from Secret Manager, as
   Flow did? Installation-token minting lives in `RepoActivitiesImpl`/`GitHubActivitiesImpl` — port
   Flow's `github-app-auth` (`GitHubAppTokenMinter`/`RefreshingGitHubAppToken`) verbatim?
5. **Workflow versioning / migration.** Changing `run()`'s control flow breaks determinism for
   in-flight histories. Policy: `Workflow.getVersion` patching vs. drain-and-restart on a new task
   queue? Worth settling before the first non-trivial change lands (this is where Farm should *not*
   re-learn Flow's lessons the hard way).
6. **Mutex-release-on-failure.** The scaffold releases the repo mutex even when a pipeline FAILs (so a
   repo is never wedged) and relies on self-heal/human retry — the opposite of Flow, which blocked all
   picks behind one uncleared post-merge failure. Confirm this is the desired default, or gate re-pick
   behind an `approve` signal.
7. **Deploy surface.** What does `triggerDeploy` actually call (the trunk apply pipeline / CD), and
   what emits the post-merge check result that `onCheckCompleted` carries?
```
