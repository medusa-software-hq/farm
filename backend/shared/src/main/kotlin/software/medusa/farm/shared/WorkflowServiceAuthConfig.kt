package software.medusa.farm.shared

import io.temporal.serviceclient.WorkflowServiceStubsOptions

/** How a Temporal service connection authenticates. */
sealed interface WorkflowServiceAuthConfig {
  fun configureBuilder(builder: WorkflowServiceStubsOptions.Builder)

  data class Cloud(val apiKey: String) : WorkflowServiceAuthConfig {
    override fun configureBuilder(builder: WorkflowServiceStubsOptions.Builder) {
      builder.setEnableHttps(true)
      builder.addApiKey { apiKey }
    }
  }

  data object Local : WorkflowServiceAuthConfig {
    override fun configureBuilder(builder: WorkflowServiceStubsOptions.Builder) {}
  }
}
