package software.medusa.farm.cli.api

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import software.medusa.farm.cli.auth.IdTokenProvider

/**
 * Attaches the caller's Google ID token as an `Authorization: Bearer` header on every gRPC call.
 * Attach it to the stub once; gRPC runs it per call, fetching (and silently refreshing) the token
 * from [idTokenProvider] as each call starts — on the calling thread for a blocking stub — so a
 * lapsed session surfaces as NotLoggedInException before the request leaves, not as a gRPC error.
 */
class BearerTokenInterceptor(private val idTokenProvider: IdTokenProvider) : ClientInterceptor {
  override fun <ReqT, RespT> interceptCall(
      method: MethodDescriptor<ReqT, RespT>,
      callOptions: CallOptions,
      next: Channel,
  ): ClientCall<ReqT, RespT> =
      object : SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        override fun start(responseListener: ClientCall.Listener<RespT>, headers: Metadata) {
          headers.put(AUTHORIZATION, "Bearer ${idTokenProvider.provideFreshIdToken()}")
          super.start(responseListener, headers)
        }
      }

  companion object {
    private val AUTHORIZATION: Metadata.Key<String> =
        Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
  }
}
