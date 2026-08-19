package software.medusa.farm.worker

import io.temporal.activity.Activity
import software.medusa.farm.shared.AttemptNumbering

/**
 * Takes the number from the running activity, so a try recorded against a run and a try in the
 * workflow's history are the same try. Only callable from within an activity.
 */
class TemporalAttemptNumbering : AttemptNumbering {
  override fun currentAttempt(): Int = Activity.getExecutionContext().info.attempt
}
