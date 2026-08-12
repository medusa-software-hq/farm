package software.medusa.farm.shared

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * The periodic sweep that backstops the on-link sync: fans out to a per-installation
 * [RepoSyncWorkflow] so every linked org converges even when its on-link sync was skipped (Temporal
 * or the worker was momentarily down). Driven by a Temporal Schedule. Shared so the schedule's
 * start action and the worker's registration derive the workflow type from the same interface.
 */
@WorkflowInterface
interface SyncAllReposWorkflow {
  @WorkflowMethod fun syncAll()
}
