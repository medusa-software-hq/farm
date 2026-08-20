package software.medusa.farm.github

/**
 * The App-management surface, driven by the app's private-key JWT rather than a bearer token: the
 * endpoints only the app itself can reach, used to turn an org into the installation artifacts Farm
 * stores and mints against.
 */
interface GhAppApiClient {
  suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId

  /**
   * The permissions the App is configured with, which is the most any installation of it can have
   * been granted — an installation holds these only once its org has approved them.
   */
  suspend fun fetchDeclaredPermissions(): GhAppPermissionSet

  suspend fun mintInstallationToken(installationId: GhInstallationId): MintedGhInstallationToken
}
