package software.medusa.farm.github

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies a valid installation token for one org, resolving the installation on first use and
 * re-minting the token as it nears expiry. Lazily resolving the id (which never changes, so it is
 * cached once) is what lets a client be constructed with no network. Coroutine-safe; one per org.
 */
class GhRefreshingInstallationTokenProvider(
    private val appApiClient: GhAppApiClient,
    private val orgLogin: GhOrgLogin,
    private val refreshMargin: Duration = Duration.ofMinutes(5),
    private val now: () -> Instant = Instant::now,
) : GhTokenProvider {
  private val mutex = Mutex()
  private var installationId: GhInstallationId? = null
  private var cached: MintedGhInstallationToken? = null

  override suspend fun provideToken(): String = mutex.withLock {
    val id =
        installationId ?: appApiClient.resolveInstallationId(orgLogin).also { installationId = it }
    val existing = cached
    if (existing == null || !now().isBefore(existing.expiresAt.minus(refreshMargin))) {
      cached = appApiClient.mintInstallationToken(id)
    }
    cached!!.token
  }
}
