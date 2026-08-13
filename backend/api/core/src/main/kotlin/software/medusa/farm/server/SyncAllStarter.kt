package software.medusa.farm.server

/**
 * Starts the all-orgs repo-sync sweep on demand — the manual counterpart to the periodic schedule.
 * Unlike [RepoSyncStarter], which is best-effort so a link never fails on a down worker, this is
 * called from an explicit "sync now" request: it **throws** when the sweep cannot be started, so
 * the caller (button, CLI) sees the failure instead of a silent no-op. Behind an interface so the
 * environment picks the implementation at the entry point.
 */
interface SyncAllStarter {
  suspend fun start()
}
