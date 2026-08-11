package software.medusa.farm.local

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory
import software.medusa.farm.github.GhCachingInstallationApiClientProvider
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.server.GitHubOrgService
import software.medusa.farm.server.NoOpAuthDecorator
import software.medusa.farm.server.TemporalFibonacciStarter
import software.medusa.farm.server.buildServer
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.InMemoryCounterStore
import software.medusa.farm.shared.InMemoryFibonacciStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.worker.TemporalWorkerHost

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

private const val localTemporalAddress = "localhost:7233"
private const val localTemporalNamespace = "default"

// The Medusa Farm (Test) app (org `medusa-software-test-hq`); public app identifier, not a secret.
private const val testGitHubAppClientId = "Iv23liUa4I1Mh1CZwWaH"

private const val devGitHubAppPemPathEnvVarName = "FARM_DEV_GITHUB_APP_PEM_PATH"

private val logger = LoggerFactory.getLogger("software.medusa.farm.local.Main")

/**
 * The one-process local stack: the API and an in-process Fibonacci worker over one shared in-memory
 * [FarmStore].
 *
 * Tolerant of an absent local Temporal server: if the worker can't connect, log it and keep serving
 * — only starting a workflow degrades.
 */
fun main() {
  val farmStore =
      FarmStore(InMemoryCounterStore(), InMemoryFibonacciStore(), InMemoryLinkedOrgStore())

  try {
    TemporalWorkerHost(
            address = localTemporalAddress,
            namespace = localTemporalNamespace,
            authConfig = WorkflowServiceAuthConfig.Local,
            store = farmStore.fibonacci,
        )
        .start()
  } catch (e: Exception) {
    logger.warn(
        "Temporal dev server unreachable at {}; serving the API without a worker " +
            "(StartFibonacci will degrade). Start it with `temporal server start-dev`.",
        localTemporalAddress,
        e,
    )
  }

  buildServer(
          originRegex = localCorsOriginRegex,
          port = localPort,
          auth = NoOpAuthDecorator,
          farmStore = farmStore,
          fibonacciStarter =
              TemporalFibonacciStarter(
                  localTemporalAddress,
                  localTemporalNamespace,
                  WorkflowServiceAuthConfig.Local,
              ),
          gitHubOrgs = buildGitHubOrgs(farmStore.linkedOrg),
      )
      .start()
      .join()
}

/**
 * The Test app's collaborator when its private key is on disk (fetched by the dev task), else null
 * so offline dev with no key still runs — the API's guard degrades the GitHub RPCs.
 */
private fun buildGitHubOrgs(linkedOrgStore: LinkedOrgStore): GitHubOrgService? {
  val pemPath =
      System.getenv(devGitHubAppPemPathEnvVarName)?.let { Paths.get(it) } ?: defaultDevPemPath()
  if (!Files.isRegularFile(pemPath)) {
    logger.info(
        "No Test GitHub App key at {}; serving the API without it (GitHub RPCs degrade). " +
            "Run `task dev` (or `task fetch-dev-github-key`) to fetch it.",
        pemPath,
    )
    return null
  }
  val appApiClient = GhProperAppApiClient.build(testGitHubAppClientId, Files.readString(pemPath))
  return GitHubOrgService(
      appApiClient,
      GhCachingInstallationApiClientProvider(GhProperInstallationApiClientProvider(appApiClient)),
      linkedOrgStore,
  )
}

private fun defaultDevPemPath(): Path =
    Paths.get(System.getProperty("user.home"), ".config", "ms-farm", "dev-github-app.pem")
