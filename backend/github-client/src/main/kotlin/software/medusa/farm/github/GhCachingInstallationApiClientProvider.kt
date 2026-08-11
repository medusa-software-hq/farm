package software.medusa.farm.github

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one installation client per org, so an org's resolved installation id and current token are
 * reused across calls instead of re-resolved and re-minted each time.
 */
class GhCachingInstallationApiClientProvider(
    private val delegate: GhInstallationApiClientProvider,
) : GhInstallationApiClientProvider {
  private val clientsByOrg = ConcurrentHashMap<GhOrgLogin, GhInstallationApiClient>()

  override fun provideForOrg(orgLogin: GhOrgLogin): GhInstallationApiClient =
      clientsByOrg.computeIfAbsent(orgLogin) { delegate.provideForOrg(it) }
}
