package software.medusa.farm.worker

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/** Syncs one installation's repos from GitHub into the Farm-owned repos table. */
@WorkflowInterface
interface RepoSyncWorkflow {
  @WorkflowMethod fun sync(installationId: Long)
}
