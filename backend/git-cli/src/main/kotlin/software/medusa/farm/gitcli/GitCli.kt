package software.medusa.farm.gitcli

import java.nio.file.Path

/**
 * The `git` CLI operations Farm's publish flow needs and the commons `git` library doesn't provide:
 * cloning a remote, branching, staging, committing (optionally signed), and pushing. Every method
 * throws [GitCliException] on a non-zero git exit.
 *
 * Auth is a GitHub token supplied to the remote operations ([clone], [push]) — never placed in the
 * URL or argv. Implementations are responsible for feeding it to git without leaking it.
 */
interface GitCli {
  /** Clones [remoteUrl] into [into] (which must not yet exist), authenticating with [token]. */
  suspend fun clone(remoteUrl: String, into: Path, token: String)

  /** The name of the currently checked-out branch. */
  suspend fun currentBranch(repo: Path): String

  /** Creates [branch] off the current HEAD and checks it out. */
  suspend fun createBranch(repo: Path, branch: String)

  /** Checks out an existing branch — one a clone already fetched, not a new one. */
  suspend fun checkout(repo: Path, branch: String)

  /** Stages every change in the working tree (`git add -A`). */
  suspend fun stageAll(repo: Path)

  /** Whether the index holds changes to commit. */
  suspend fun hasStagedChanges(repo: Path): Boolean

  /**
   * Commits the staged changes as [author]. When [signingKey] is non-null the commit is GPG-signed
   * with that key (the signer must be available in the environment's keyring); otherwise it is
   * explicitly unsigned.
   */
  suspend fun commit(repo: Path, message: String, author: GitCliAuthor, signingKey: String?)

  /** Pushes [branch] to `origin`, authenticating with [token]. */
  suspend fun push(repo: Path, branch: String, token: String)

  /** The full commit hash at HEAD. */
  suspend fun headSha(repo: Path): String
}
