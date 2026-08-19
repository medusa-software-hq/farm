package software.medusa.farm.ephemeral

import kotlinx.coroutines.CompletableDeferred
import software.medusa.farm.github.GhCachingInstallationApiClientProvider
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.server.GitHubOrgService
import software.medusa.farm.server.NoOpAuthDecorator
import software.medusa.farm.server.TemporalRepoSyncStarter
import software.medusa.farm.server.TemporalSyncAllStarter
import software.medusa.farm.server.buildServer
import software.medusa.farm.server.buildWorkflowClient
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.FarmWorker
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.worker.RunSummarizer
import software.medusa.farm.worker.TemporalWorkerHost
import software.medusa.farm.worker.WorkerConfig

/**
 * The whole farm — worker and API — in this process, against a database and a GitHub org that exist
 * to be thrown away.
 *
 * Temporal is the one thing not reached over the network: a server started beside this one, which
 * is why a run needs no credential for it and cannot collide with another run. What it gives up is
 * the connection to Temporal Cloud, which the deployment exercises continuously anyway; the
 * workflow semantics under test are the same server's.
 */
object EphemeralFarm {
  /**
   * Migrates the database, starts the farm, and runs [block] against it. Everything started here is
   * stopped when the block ends, however it ends.
   *
   * @return whatever [block] returned.
   */
  fun <ResultT> run(config: EphemeralConfig, block: (EphemeralFarmScope) -> ResultT): ResultT {
    FarmStore.migrate(config.databaseUrl)
    val farmStore = FarmStore.build(config.databaseUrl)

    val appApiClient = GhProperAppApiClient.build(config.gitHubApp.clientId, config.gitHubApp.pem)
    val clientProvider =
        GhCachingInstallationApiClientProvider(GhProperInstallationApiClientProvider(appApiClient))

    // The default queue, because this process is the only worker against this server — there is
    // nothing here to take work from.
    val taskQueue = FarmWorker.DEFAULT_TASK_QUEUE
    val workerHost =
        TemporalWorkerHost(
            address = TEMPORAL_ADDRESS,
            namespace = TEMPORAL_NAMESPACE,
            authConfig = WorkflowServiceAuthConfig.Local,
            taskQueue = taskQueue,
            repoStore = farmStore.repo,
            issueStore = farmStore.issue,
            sessionStore = farmStore.session,
            linkedOrgStore = farmStore.linkedOrg,
            gitHubClientProvider = clientProvider,
            appApiClient = appApiClient,
            claudeOauthToken = config.claudeOauthToken,
            summarizer = RunSummarizer.from(config.openRouterApiKey),
            commitAuthor = WorkerConfig.commitAuthorFrom(System.getenv()),
            signingKey = WorkerConfig.signingKeyFrom(System.getenv()),
        )
    workerHost.start()

    val temporalClient =
        CompletableDeferred(
            buildWorkflowClient(
                TEMPORAL_ADDRESS,
                TEMPORAL_NAMESPACE,
                WorkflowServiceAuthConfig.Local,
            )
        )

    val server =
        buildServer(
            originRegex = ORIGIN_REGEX,
            port = config.apiPort,
            // The loop is what is under test, not who may watch it.
            auth = NoOpAuthDecorator,
            farmStore = farmStore,
            gitHubOrgs =
                GitHubOrgService(
                    appApiClient,
                    farmStore.linkedOrg,
                    TemporalRepoSyncStarter(temporalClient, taskQueue),
                ),
            syncAllStarter = TemporalSyncAllStarter(temporalClient, taskQueue),
        )
    server.start().join()

    return try {
      block(EphemeralFarmScope(apiPort = server.activeLocalPort(), appApiClient = appApiClient))
    } finally {
      server.stop().join()
    }
  }

  private const val TEMPORAL_ADDRESS = "localhost:7233"

  private const val TEMPORAL_NAMESPACE = "default"

  // Nothing browses this farm; the regex only has to be one the server accepts.
  private const val ORIGIN_REGEX = "^http://localhost:\\d+$"
}
