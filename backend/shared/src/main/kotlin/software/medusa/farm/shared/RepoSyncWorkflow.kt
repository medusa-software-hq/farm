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

/**
 * The stable Temporal workflow id for an installation's sync. Shared so the on-link starter and the
 * periodic sweep target the SAME id — with USE_EXISTING they dedupe against each other rather than
 * running two syncs for one org — and the id lives in exactly one place.
 */
fun repoSyncWorkflowId(installationId: Long): String = "repo-sync:$installationId"
