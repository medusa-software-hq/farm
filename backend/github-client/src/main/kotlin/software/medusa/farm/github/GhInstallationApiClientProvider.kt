package software.medusa.farm.github

/**
 * Hands out an installation client per org. Pure construction — no network — so callers can build
 * one anywhere; the first actual request is what resolves the installation and mints a token.
 */
interface GhInstallationApiClientProvider {
  fun provideForOrg(orgLogin: GhOrgLogin): GhInstallationApiClient
}
