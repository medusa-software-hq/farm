package software.medusa.farm.github

/**
 * The App-management surface, driven by the app's private-key JWT rather than a bearer token: the
 * endpoints only the app itself can reach, used to turn an org into the installation artifacts Farm
 * stores and mints against.
 */
interface GhAppApiClient {
  suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId

  suspend fun mintInstallationToken(installationId: GhInstallationId): MintedGhInstallationToken
}
