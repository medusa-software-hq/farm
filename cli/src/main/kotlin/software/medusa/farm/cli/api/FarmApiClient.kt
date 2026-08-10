package software.medusa.farm.cli.api

import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.TlsChannelCredentials
import java.util.concurrent.TimeUnit
import software.medusa.farm.cli.auth.IdTokenProvider
import software.medusa.farm.v1.DecrementRequest
import software.medusa.farm.v1.FarmServiceGrpc
import software.medusa.farm.v1.FarmServiceGrpc.FarmServiceBlockingStub
import software.medusa.farm.v1.GetCountRequest
import software.medusa.farm.v1.IncrementRequest

/**
 * Talks to FarmService over gRPC. A [BearerTokenInterceptor] — attached to the stub once — puts the
 * caller's Google ID token on every call, fetched (and silently refreshed) via [idTokenProvider] as
 * the call starts, so a lapsed session surfaces as NotLoggedInException rather than a gRPC error.
 * [close] shuts the channel down — the CLI uses one client per command.
 */
class FarmApiClient(endpoint: ApiEndpoint, idTokenProvider: IdTokenProvider) : AutoCloseable {
  private val channel: ManagedChannel = channelFor(endpoint)
  private val stub: FarmServiceBlockingStub =
      FarmServiceGrpc.newBlockingStub(channel)
          .withInterceptors(BearerTokenInterceptor(idTokenProvider))

  fun getCount(): Int = call { stub.getCount(GetCountRequest.getDefaultInstance()).count }

  fun increment(): Int = call { stub.increment(IncrementRequest.getDefaultInstance()).count }

  fun decrement(): Int = call { stub.decrement(DecrementRequest.getDefaultInstance()).count }

  private inline fun <T> call(block: () -> T): T =
      try {
        block()
      } catch (e: StatusRuntimeException) {
        throw asApiException(e)
      }

  override fun close() {
    channel.shutdownNow()
    channel.awaitTermination(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)
  }

  companion object {
    private const val SHUTDOWN_TIMEOUT_SEC = 5L

    private fun channelFor(endpoint: ApiEndpoint): ManagedChannel {
      val credentials =
          if (endpoint.useTls) TlsChannelCredentials.create()
          else InsecureChannelCredentials.create()
      return Grpc.newChannelBuilderForAddress(endpoint.host, endpoint.port, credentials).build()
    }

    private fun asApiException(e: StatusRuntimeException): ApiException =
        when (e.status.code) {
          Status.Code.UNAUTHENTICATED,
          Status.Code.PERMISSION_DENIED ->
              ApiException(
                  "The API rejected your identity (${e.status.code}). Your session may have lapsed, " +
                      "or your account isn't allowed — try 'ms-farm login' again."
              )
          else -> ApiException("API error (${e.status.code}): ${e.status.description ?: e.message}")
        }
  }
}
