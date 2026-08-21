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
  fun process(
      installationId: Long,
      githubRepoId: Long,
      repoFullName: String,
      number: Int,
      title: String,
  )
}

/**
 * The stable Temporal workflow id for processing one issue: one run at a time per issue, since the
 * sweep re-attempts every ready issue on every pass and a second run alongside the first would work
 * the same issue twice over. Lives in one place so the start site and any future re-trigger target
 * the same id.
 *
 * Only while a run is in flight. Whether a *finished* issue is worked again is asked of the label,
 * not of the id: a closed execution is forgotten when the namespace's retention runs out, so an id
 * cannot say anything that has to outlast it.
 */
fun processIssueWorkflowId(githubRepoId: Long, number: Int): String =
    "process-issue:$githubRepoId:$number"
