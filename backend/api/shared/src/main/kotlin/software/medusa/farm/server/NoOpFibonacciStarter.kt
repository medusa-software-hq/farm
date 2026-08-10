package software.medusa.farm.server

import io.grpc.Status

/**
 * Starter for when there is no Temporal to reach (local dev, or the API before its key is wired).
 */
object NoOpFibonacciStarter : FibonacciStarter {
  override fun start(through: Int): String =
      throw Status.UNIMPLEMENTED.withDescription(
              "StartFibonacci is unavailable: Temporal is not configured."
          )
          .asException()
}
