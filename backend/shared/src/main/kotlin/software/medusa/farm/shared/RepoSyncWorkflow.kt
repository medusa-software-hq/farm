package software.medusa.farm.shared

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Syncs one installation's repos from GitHub into the Farm-owned repos table. Shared so the API's
 * typed start stub and the worker's registration derive the workflow type from the same interface —
 * the type name cannot drift.
 */
@WorkflowInterface
interface RepoSyncWorkflow {
  @WorkflowMethod fun sync(installationId: Long)
}
