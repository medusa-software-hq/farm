package software.medusa.farm.github

/**
 * Hands out an installation client per installation. Pure construction — no network — so callers
 * can build one anywhere; the first actual request is what mints a token.
 */
interface GhInstallationApiClientProvider {
  fun provideForInstallation(installationId: GhInstallationId): GhInstallationApiClient
}
