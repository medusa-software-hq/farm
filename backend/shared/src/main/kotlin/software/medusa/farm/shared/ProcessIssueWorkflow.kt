package software.medusa.farm.shared

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Processes one issue: opens a session, then (for now) posts a starting comment, waits, and posts a
 * completed comment — a fake stand-in for the real AI work to come. Shared so the worker's
 * registration and the sweep's start stub derive the workflow type from the same interface.
 */
@WorkflowInterface
interface ProcessIssueWorkflow {
  @WorkflowMethod
  fun process(installationId: Long, githubRepoId: Long, repoFullName: String, number: Int)
}

/**
 * The stable Temporal workflow id for processing one issue. Combined with REJECT_DUPLICATE at the
 * start site, it makes processing run once per issue: the hourly sweep re-attempts every open
 * issue, but an id that already ran is rejected rather than re-processed (and re-commented). Lives
 * in one place so the start site and any future re-trigger target the same id.
 */
fun processIssueWorkflowId(githubRepoId: Long, number: Int): String =
    "process-issue:$githubRepoId:$number"
