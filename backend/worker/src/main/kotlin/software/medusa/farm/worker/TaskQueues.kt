package software.medusa.farm.worker

/**
 * Task-queue names, defined once and shared by the worker (registration) and the api (workflow
 * start), mirroring the org Temporal demo's single `TaskQueue` const so worker and clients never
 * drift.
 *
 * Farm splits into TWO queues in one worker process (DESIGN.md §2.4, §6.2): [pipeline] carries the
 * workflow tasks + all light activities, and [engine] carries ONLY the long `runEngine` activity,
 * so a saturated 2-hour engine run can't starve the seconds-long orchestration/GitHub/DB
 * activities. `runEngine` is pinned to [engine] via `ActivityOptions.setTaskQueue`. The split is a
 * concurrency boundary now and the relocation seam if the engine ever moves to a separate fleet.
 */
object TaskQueues {
  const val pipeline: String = "farm-pipeline"
  const val engine: String = "farm-engine"
}
