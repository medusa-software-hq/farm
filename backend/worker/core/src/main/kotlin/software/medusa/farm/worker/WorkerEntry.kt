package software.medusa.farm.worker

import software.medusa.farm.github.GhCachingInstallationApiClientProvider
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.summary.SumRunSummarizer

/** Runs the farm's Temporal worker over [config] and blocks, staying up to process tasks. */
fun runTemporalWorker(config: WorkerConfig) {
  val store = FarmStore.build(config.databaseUrl)
  // One App client, used both to mint the per-installation API clients and to mint raw git tokens.
  val appApiClient = GhProperAppApiClient.build(config.gitHubApp.clientId, config.gitHubApp.pem)
  val clientProvider =
      GhCachingInstallationApiClientProvider(GhProperInstallationApiClientProvider(appApiClient))
  TemporalWorkerHost(
          config.temporalAddress,
          config.temporalNamespace,
          config.temporalAuth,
          store.repo,
          store.issue,
          store.session,
          store.linkedOrg,
          clientProvider,
          appApiClient,
          config.claudeOauthToken,
          // The run summarizer reads OPENROUTER_API_KEY from the launch environment, like the
          // claude
          // token above — the worker runs on the operator's machine.
          SumRunSummarizer.fromEnv(System::getenv),
          config.commitAuthor,
          config.signingKey,
      )
      .start()
  // Stay up; the worker factory polls on background threads.
  Thread.currentThread().join()
}
