package software.medusa.farm.shared

/** Which try at the work in hand this is, counting from one. */
interface AttemptNumbering {
  fun currentAttempt(): Int
}
