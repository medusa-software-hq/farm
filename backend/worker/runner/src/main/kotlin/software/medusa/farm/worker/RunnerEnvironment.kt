package software.medusa.farm.worker

import software.medusa.farm.shared.BakedGitHubAppClientIds

/**
 * A remote environment the runner can target, each pinned to its stable (non-secret) GCP project
 * and the GitHub App client id it authenticates as (both baked, not passed at runtime).
 */
enum class RunnerEnvironment(
    val envValue: String,
    val gcpProjectId: String,
    val gitHubAppClientId: String,
) {
  PROD("prod", "ms-farm-11efee2b", BakedGitHubAppClientIds.PROD),
  STAGING("staging", "ms-farm-98981146", BakedGitHubAppClientIds.STAGING),
}
