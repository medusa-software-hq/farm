package software.medusa.farm.server

/**
 * Kicks off a background repo sync for one installation. Fire-and-forget and best-effort by
 * contract: a link must succeed even when the sync cannot be started (Temporal or the worker is
 * down), so implementations never throw. Behind an interface so the environment (real Temporal vs.
 * a local no-op) is chosen at the entry point, keeping the API decoupled from the worker module.
 */
interface RepoSyncStarter {
  suspend fun start(installationId: Long)
}
