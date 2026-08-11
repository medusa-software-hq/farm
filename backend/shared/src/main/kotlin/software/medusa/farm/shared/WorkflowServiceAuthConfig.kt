package software.medusa.farm.shared

import io.temporal.serviceclient.WorkflowServiceStubsOptions

/**
 * How a Temporal service connection authenticates. Injected into the API's starter and the worker's
 * host so the connection shape is chosen at the entry point, not branched on inside either class.
 */
sealed interface WorkflowServiceAuthConfig {
  fun configureBuilder(builder: WorkflowServiceStubsOptions.Builder)

  /** Temporal Cloud: an API key over TLS. */
  data class ApiKey(val apiKey: String) : WorkflowServiceAuthConfig {
    override fun configureBuilder(builder: WorkflowServiceStubsOptions.Builder) {
      builder.setEnableHttps(true)
      builder.addApiKey { apiKey }
    }
  }

  /** A local Temporal dev server: plaintext, no TLS, no key. */
  data object None : WorkflowServiceAuthConfig {
    override fun configureBuilder(builder: WorkflowServiceStubsOptions.Builder) {}
  }
}
