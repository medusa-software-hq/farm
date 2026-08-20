package software.medusa.farm.ephemeral

import kotlinx.coroutines.CompletableDeferred
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.server.GitHubOrgService
import software.medusa.farm.server.NoOpAuthDecorator
import software.medusa.farm.server.TemporalRepoSyncStarter
import software.medusa.farm.server.TemporalSyncAllStarter
import software.medusa.farm.server.buildServer
import software.medusa.farm.server.buildWorkflowClient
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.worker.buildTemporalWorker

// Nothing browses this farm; the regex only has to be one the server accepts.
private const val originRegex = "^http://localhost:\\d+$"

/**
 * A whole farm — worker and API — in one process, against a database and a GitHub org that exist to
 * be thrown away. Built like the deployed one rather than like the local one: real stores, real
 * migrations, the real agent, and a worker wired from the same configuration the deployed one
 * takes.
 *
 * The worker and the API keep their own database connections and their own GitHub client, as they
 * do when they are two deployments rather than one process. Sharing them here would be less work
 * and less true: they would then contend for one pool where they have one each, and this exists to
 * behave the way the real thing behaves.
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
  val workerConfig = config.toWorkerConfig()

  // A deploy step where this is deployed, and there is no deploy here.
  FarmStore.migrate(config.databaseUrl)

  buildTemporalWorker(workerConfig).start()

  val apiStore = FarmStore.build(config.databaseUrl)
  val appApiClient =
      GhProperAppApiClient.build(config.gitHubApp.clientId, config.gitHubApp.privateKey)
  val temporalClient =
      CompletableDeferred(
          buildWorkflowClient(
              EphemeralConfig.TEMPORAL_ADDRESS,
              EphemeralConfig.TEMPORAL_NAMESPACE,
              WorkflowServiceAuthConfig.Local,
          )
      )

  buildServer(
          originRegex = originRegex,
          port = config.apiPort,
          // What is under test is the loop, not who may watch it.
          auth = NoOpAuthDecorator,
          farmStore = apiStore,
          gitHubOrgs =
              GitHubOrgService(
                  appApiClient,
                  apiStore.linkedOrg,
                  TemporalRepoSyncStarter(temporalClient, workerConfig.taskQueue),
              ),
          syncAllStarter = TemporalSyncAllStarter(temporalClient, workerConfig.taskQueue),
      )
      .start()
      .join()

  // Stays up until it is stopped; whatever drives it does so over the API.
  Thread.currentThread().join()
}
