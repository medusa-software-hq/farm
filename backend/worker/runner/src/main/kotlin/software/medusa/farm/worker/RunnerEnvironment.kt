package software.medusa.farm.worker

/**
 * A remote environment the runner can target, each pinned to its stable (non-secret) GCP project.
 */
enum class RunnerEnvironment(val envValue: String, val gcpProjectId: String) {
  PROD("prod", "ms-farm-11efee2b"),
  STAGING("staging", "ms-farm-98981146"),
}
