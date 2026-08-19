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

private const val temporalAddress = "localhost:7233"

private const val temporalNamespace = "default"

// Nothing browses this farm; the regex only has to be one the server accepts.
private const val originRegex = "^http://localhost:\\d+$"

/**
 * A whole farm — worker and API — in one process, against a database and a GitHub org that exist to
 * be thrown away. Built like the deployed one rather than like the local one: real stores, real
 * migrations, the real agent.
 *
 * Temporal is the one thing not reached over the network: a server started beside this one, which
 * is why running this needs no Temporal credential and two of these cannot collide. What that gives
 * up is the connection to Temporal Cloud, which the deployment exercises continuously; the workflow
 * semantics are the same server's.
 *
 * Runs until it is stopped. Whatever drives it — a system test, or a person — reaches it over the
 * API like anything else would.
 */
fun main() {
  val config = EphemeralConfig.fromEnvironment()

  FarmStore.migrate(config.databaseUrl)
  val farmStore = FarmStore.build(config.databaseUrl)

  val appApiClient = GhProperAppApiClient.build(config.gitHubApp.clientId, config.gitHubApp.pem)
  val clientProvider =
      GhCachingInstallationApiClientProvider(GhProperInstallationApiClientProvider(appApiClient))

  // The default queue: this process is the only worker against its own Temporal, so there is
  // nothing here to take work from.
  val taskQueue = FarmWorker.DEFAULT_TASK_QUEUE

  TemporalWorkerHost(
          address = temporalAddress,
          namespace = temporalNamespace,
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
      .start()

  val temporalClient =
      CompletableDeferred(
          buildWorkflowClient(temporalAddress, temporalNamespace, WorkflowServiceAuthConfig.Local)
      )

  buildServer(
          originRegex = originRegex,
          port = config.apiPort,
          // What is under test is the loop, not who may watch it.
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
      .start()
      .join()

  // Stays up until it is stopped; whatever drives it does so over the API.
  Thread.currentThread().join()
}
