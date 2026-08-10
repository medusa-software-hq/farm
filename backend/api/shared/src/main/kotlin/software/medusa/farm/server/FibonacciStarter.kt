package software.medusa.farm.server

/**
 * Starts the Fibonacci computation, returning the started workflow's id. Kept behind an interface
 * so the environment (real Temporal vs. local no-op) is chosen at the entry point, and so a
 * Temporal-unreachable start fails only that call — the counter and ListFibonacci are unaffected.
 */
interface FibonacciStarter {
  /** Starts computing the sequence through [through]; returns the workflow id. */
  fun start(through: Int): String
}
