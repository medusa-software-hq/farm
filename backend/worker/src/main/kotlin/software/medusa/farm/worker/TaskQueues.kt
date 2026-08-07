package software.medusa.farm.worker

/**
 * Task-queue names, defined once and shared by the worker (registration) and the api (workflow
 * start), mirroring the org Temporal demo's single `TaskQueue` const so worker and clients never
 * drift.
 *
 * A single queue is the default. If the long engine run needs to be isolated from lightweight
 * orchestration (so a saturated engine cannot starve pipeline decisions), split into [pipeline] + a
 * dedicated `farm-engine` queue and register the engine activity there — see DESIGN.md, "Task-queue
 * partitioning".
 */
object TaskQueues {
  const val pipeline: String = "farm-pipeline"
}
