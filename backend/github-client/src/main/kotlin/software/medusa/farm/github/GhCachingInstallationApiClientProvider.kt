package software.medusa.farm.github

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one installation client per installation, so its current token is reused across calls
 * instead of re-minted each time.
 */
class GhCachingInstallationApiClientProvider(
    private val delegate: GhInstallationApiClientProvider,
) : GhInstallationApiClientProvider {
  private val clientsByInstallation = ConcurrentHashMap<GhInstallationId, GhInstallationApiClient>()

  override fun provideForInstallation(installationId: GhInstallationId): GhInstallationApiClient =
      clientsByInstallation.computeIfAbsent(installationId) { delegate.provideForInstallation(it) }
}
