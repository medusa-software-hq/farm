package software.medusa.farm.worker

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * The real coding work: attempt an issue with the agent and, if it changed anything, open a PR.
 *
 * Ids and names cross as primitives and are wrapped on arrival, rather than crossing as the value
 * classes they belong in. That is not a stylistic preference: an `@JvmInline value class` parameter
 * is erased to its underlying type in the JVM signature, so nothing would be preserved by passing
 * one — and it mangles the method name with a hash of the signature, which is what Temporal names
 * the activity type after. The name would then change whenever a parameter did, and a running
 * workflow would stop finding the activity it was waiting on.
 */
@ActivityInterface
interface PublishActivities {
  /**
   * Clones the repo, runs the agent against the issue, and — if it produced changes — commits them
   * to a branch, pushes, and opens a pull request. Returns the PR URL, or `null` for no changes.
   * Throws (so the caller can fail the session) if the run does not complete cleanly.
   */
  @ActivityMethod
  fun attemptIssue(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
  ): IssueAttemptOutcome

  /**
   * Runs [feedback] against the branch the session's pull request is on, as run [runOrdinal], and
   * pushes whatever the agent changed. The pull request updates itself; nothing is opened.
   *
   * The session is a fresh one — the run being followed up is gone — so what it did is carried
   * forward as its summary rather than as a session to resume.
   */
  @ActivityMethod
  fun fixupIssue(
      sessionId: String,
      installationId: Long,
      repoFullName: String,
      number: Int,
      title: String,
      runOrdinal: Int,
      feedback: ReviewFeedback,
  )
}
