package software.medusa.farm.local

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import software.medusa.farm.github.GhCachingInstallationApiClientProvider
import software.medusa.farm.github.GhInstallationApiClientProvider
import software.medusa.farm.github.GhProperAppApiClient
import software.medusa.farm.github.GhProperInstallationApiClientProvider
import software.medusa.farm.server.GitHubOrgService
import software.medusa.farm.server.NoOpAuthDecorator
import software.medusa.farm.server.TemporalFibonacciStarter
import software.medusa.farm.server.TemporalRepoSyncStarter
import software.medusa.farm.server.buildServer
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.InMemoryFibonacciStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.InMemoryRepoStore
import software.medusa.farm.shared.WorkflowServiceAuthConfig
import software.medusa.farm.worker.TemporalWorkerHost

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

private const val localTemporalAddress = "localhost:7233"
private const val localTemporalNamespace = "default"

// The Medusa Farm (Test) app (org `medusa-software-test-hq`); public app identifier, not a secret.
private const val testGitHubAppClientId = "Iv23liUa4I1Mh1CZwWaH"

private const val devGitHubAppPemPathEnvVarName = "FARM_DEV_GITHUB_APP_PEM_PATH"

/** The App client for the Test app plus a per-installation client provider derived from it. */
private class DevGitHubApp(
    val appApiClient: GhProperAppApiClient,
    val clientProvider: GhInstallationApiClientProvider,
)

/**
 * The one-process local stack: the API and an in-process worker over one shared in-memory
 * [FarmStore], so a link-triggered repo sync writes to the same table the API reads. Requires the
 * local Temporal dev server (`task dev` starts it). With the Test app's dev key present, linking
 * `medusa-software-test-hq` performs a real sync into the in-memory repos table.
 */
fun main() {
  val farmStore =
      FarmStore(
          InMemoryFibonacciStore(),
          InMemoryLinkedOrgStore(),
          InMemoryRepoStore(Clock.systemUTC()),
      )

  val devGitHubApp = buildDevGitHubApp()

  TemporalWorkerHost(
          address = localTemporalAddress,
          namespace = localTemporalNamespace,
          authConfig = WorkflowServiceAuthConfig.Local,
          fibonacciStore = farmStore.fibonacci,
          repoStore = farmStore.repo,
          linkedOrgStore = farmStore.linkedOrg,
          gitHubClientProvider = devGitHubApp.clientProvider,
      )
      .start()

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
          gitHubOrgs =
              GitHubOrgService(
                  devGitHubApp.appApiClient,
                  farmStore.linkedOrg,
                  TemporalRepoSyncStarter(
                      localTemporalAddress,
                      localTemporalNamespace,
                      WorkflowServiceAuthConfig.Local,
                  ),
              ),
      )
      .start()
      .join()
}

/**
 * The Test app, from its private key on disk (fetched by the dev task). Required: `task dev`
 * fetches the key, so a missing one is a setup slip — fail fast rather than run a half-working
 * stack.
 */
private fun buildDevGitHubApp(): DevGitHubApp {
  val pemPath =
      System.getenv(devGitHubAppPemPathEnvVarName)?.let { Paths.get(it) } ?: defaultDevPemPath()
  require(Files.isRegularFile(pemPath)) {
    "No Test GitHub App key at $pemPath. Run `task dev` (or `task fetch-dev-github-key`) to fetch it."
  }
  val appApiClient = GhProperAppApiClient.build(testGitHubAppClientId, Files.readString(pemPath))
  return DevGitHubApp(
      appApiClient,
      GhCachingInstallationApiClientProvider(GhProperInstallationApiClientProvider(appApiClient)),
  )
}

private fun defaultDevPemPath(): Path =
    Paths.get(System.getProperty("user.home"), ".config", "ms-farm", "dev-github-app.pem")
