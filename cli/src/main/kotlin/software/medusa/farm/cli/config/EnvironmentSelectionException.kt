package software.medusa.farm.cli.config

/** Raised when `FARM_ENVIRONMENT` (or a `local`-only variable) is set to something unusable. */
class EnvironmentSelectionException(message: String) : Exception(message)
