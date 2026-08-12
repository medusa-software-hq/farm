package software.medusa.farm.worker

import software.medusa.farm.github.GhCachingInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.shared.FarmStore

/** Runs the farm's Temporal worker over [config] and blocks, staying up to process tasks. */
fun runTemporalWorker(config: WorkerConfig) {
  val store = FarmStore.buildWithoutMigrations(config.databaseUrl)
  TemporalWorkerHost(
          config.temporalAddress,
          config.temporalNamespace,
          config.temporalAuth,
          store.fibonacci,
          store.repo,
          gitHubClientProvider(config.gitHubApp),
      )
      .start()
  // Stay up; the worker factory polls on background threads.
  Thread.currentThread().join()
}

private fun gitHubClientProvider(app: GitHubAppConfig): GhInstallationApiClientProvider {
  val appApiClient = GhProperAppApiClient.build(app.clientId, app.pem)
  return GhCachingInstallationApiClientProvider(GhProperInstallationApiClientProvider(appApiClient))
}
