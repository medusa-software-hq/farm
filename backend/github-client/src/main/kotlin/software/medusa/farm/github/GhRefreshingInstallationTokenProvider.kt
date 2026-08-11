package software.medusa.farm.github

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies a valid installation token for one installation, minting against its id on first use and
 * re-minting as the token nears expiry. Construction does no network — the first [provideToken] is
 * the first call. Coroutine-safe; one per installation.
 */
class GhRefreshingInstallationTokenProvider(
    private val appApiClient: GhAppApiClient,
    private val installationId: GhInstallationId,
    private val refreshMargin: Duration = Duration.ofMinutes(5),
    private val now: () -> Instant = Instant::now,
) : GhTokenProvider {
  private val mutex = Mutex()
  private var cached: MintedGhInstallationToken? = null

  override suspend fun provideToken(): String = mutex.withLock {
    val existing = cached
    if (existing == null || !now().isBefore(existing.expiresAt.minus(refreshMargin))) {
      cached = appApiClient.mintInstallationToken(installationId)
    }
    cached!!.token
  }
}
