package software.medusa.farm.server

import org.slf4j.LoggerFactory

/**
 * Starter for when there is no Temporal to reach (local dev, or the API before its key is wired).
 */
object NoOpRepoSyncStarter : RepoSyncStarter {
  private val logger = LoggerFactory.getLogger(NoOpRepoSyncStarter::class.java)

  override fun start(installationId: Long) {
    logger.info("Temporal not configured; skipping repo sync for installation {}", installationId)
  }
}
