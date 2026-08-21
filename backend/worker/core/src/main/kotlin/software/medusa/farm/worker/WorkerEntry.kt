package software.medusa.farm.worker

import kotlinx.coroutines.runBlocking
import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhAppPermissionSet
import software.medusa.farm.github.GhCachingInstallationApiClientProvider
import software.medusa.farm.github.GhPermissionId
import software.medusa.farm.github.GhPermissionMode
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.shared.FarmStore

/**
 * What the worker cannot work an issue without: it clones and pushes, comments on issues, and opens
 * pull requests to read the reviews and the checks left on them.
 *
 * Not everything the deployed App holds. Workflows:write is wanted only by a farm whose agent edits
 * a workflow file, and a farm none does is a farm that runs without it.
 */
private val requiredPermissionSet =
    GhAppPermissionSet(
        mapOf(
            GhPermissionId.Contents to GhPermissionMode.Write,
            GhPermissionId.Issues to GhPermissionMode.Write,
            GhPermissionId.PullRequests to GhPermissionMode.Write,
            GhPermissionId.Checks to GhPermissionMode.Read,
            GhPermissionId.Metadata to GhPermissionMode.Read,
        )
    )

/**
 * Refuses to wire a worker over an App that has not been granted what the worker needs, so that an
 * under-permissioned App is a farm that will not start rather than one that starts and fails the
 * first issue it is given — hours in, having already told a repository it was working.
 *
 * What it reads is what the App is configured with. An org that has not approved the App's latest
 * permissions holds less than that, and comes up short when that org is worked rather than here.
 */
private fun GhAppApiClient.checkPermissionsCover(requiredPermissionSet: GhAppPermissionSet) {
  val declaredPermissionSet = runBlocking { fetchDeclaredPermissions() }

  when (
      val coverage = GhAppPermissionSet.checkCoverage(requiredPermissionSet, declaredPermissionSet)
  ) {
    is GhAppPermissionSet.CoverageResult.Covered -> Unit
    is GhAppPermissionSet.CoverageResult.MissingPermissions ->
        error(
            "The GitHub App is not configured with ${coverage.missingPermissionSet.describe()}. " +
                "It has ${declaredPermissionSet.describe()}. Grant the rest on the App's settings " +
                "page, then approve them for each org it is installed on."
        )
  }
}

/**
 * Wires the farm's Temporal worker over [config], with a database connection and GitHub clients of
 * its own — as it has wherever it runs, whether or not something else is running beside it.
 *
 * Started by the caller, so that a process with more to start can start the rest.
 */
fun buildTemporalWorker(config: WorkerConfig): TemporalWorkerHost {
  val store = FarmStore.build(config.databaseUrl)
  // One App client, used both to mint the per-installation API clients and to mint raw git tokens.
  val appApiClient =
      GhProperAppApiClient.build(config.gitHubApp.clientId, config.gitHubApp.privateKey)
  appApiClient.checkPermissionsCover(requiredPermissionSet)

  val clientProvider =
      GhCachingInstallationApiClientProvider(GhProperInstallationApiClientProvider(appApiClient))

  return TemporalWorkerHost(
      config.temporalAddress,
      config.temporalNamespace,
      config.temporalAuth,
      config.taskQueue,
      store.repo,
      store.issue,
      store.session,
      store.linkedOrg,
      clientProvider,
      appApiClient,
      config.claudeOauthToken,
      config.claudeModel,
      config.claudeEffort,
      RunSummarizer.from(config.openRouterApiKey),
      config.commitAuthor,
      config.signingKey,
  )
}

/** Runs the farm's Temporal worker over [config] and blocks, staying up to process tasks. */
fun runTemporalWorker(config: WorkerConfig) {
  buildTemporalWorker(config).start()

  // Stay up; the worker factory polls on background threads.
  Thread.currentThread().join()
}
