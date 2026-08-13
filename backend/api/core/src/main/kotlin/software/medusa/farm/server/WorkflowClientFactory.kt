package software.medusa.farm.server

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import software.medusa.farm.shared.WorkflowServiceAuthConfig

/**
 * Builds a [WorkflowClient] for one Temporal namespace, shared across the API's starters. The
 * startup health check is disabled, so construction never blocks on Temporal reachability — the
 * gRPC channel connects lazily on the first RPC. Construction is still not free (gRPC + Netty + TLS
 * init), so the API builds it off the critical path (see main) rather than in front of the server.
 */
fun buildWorkflowClient(
    address: String,
    namespace: String,
    authConfig: WorkflowServiceAuthConfig,
): WorkflowClient {
  val builder =
      WorkflowServiceStubsOptions.newBuilder().setTarget(address).setDisableHealthCheck(true)
  authConfig.configureBuilder(builder)
  val service = WorkflowServiceStubs.newServiceStubs(builder.build())
  return WorkflowClient.newInstance(
      service,
      WorkflowClientOptions.newBuilder().setNamespace(namespace).build(),
  )
}
