package software.medusa.farm.gitcli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessOutcome
import software.medusa.commons.system.SysProcessSpawner

/**
 * The production [GitCli]: runs the git binary via the commons [SysProcessSpawner].
 *
 * A remote operation's token is supplied through `GIT_ASKPASS` (a throwaway script that echoes it
 * from an env var), never in argv or the URL, so it can't surface in `ps` output or the clone's
 * `.git/config`. The process environment is inherited so git sees the operator's config and keyring
 * (needed for signing), plus `GIT_TERMINAL_PROMPT=0` so a failed auth errors instead of hanging.
 */
class GitCliProper(
    private val spawner: SysProcessSpawner,
    private val gitExecutable: SysExecutableHandle,
) : GitCli {
  override suspend fun clone(remoteUrl: String, into: Path, token: String) {
    withAskpass(token) { askpassEnv ->
      run(
          workingDirectory = null,
          command = "clone",
          arguments = listOf("clone", remoteUrl, into.toAbsolutePath().toString()),
          extraEnv = askpassEnv,
      )
    }
  }

  override suspend fun currentBranch(repo: Path): String =
      run(repo, "rev-parse --abbrev-ref HEAD", listOf("rev-parse", "--abbrev-ref", "HEAD"))
          .standardOutput
          .trim()

  override suspend fun createBranch(repo: Path, branch: String) {
    run(repo, "checkout -b $branch", listOf("checkout", "-b", branch))
  }

  override suspend fun stageAll(repo: Path) {
    run(repo, "add -A", listOf("add", "-A"))
  }

  override suspend fun hasStagedChanges(repo: Path): Boolean {
    val outcome = spawn(repo, listOf("diff", "--cached", "--quiet"))
    return when (outcome.exitCode) {
      0 -> false
      1 -> true
      else -> throw GitCliException("diff --cached --quiet", outcome.exitCode, combined(outcome))
    }
  }

  override suspend fun commit(
      repo: Path,
      message: String,
      author: GitCliAuthor,
      signingKey: String?,
  ) {
    val signing =
        if (signingKey != null) {
          listOf("-c", "user.signingkey=$signingKey", "-c", "commit.gpgsign=true")
        } else {
          listOf("-c", "commit.gpgsign=false")
        }
    val arguments =
        listOf("-c", "user.name=${author.name}", "-c", "user.email=${author.email}") +
            signing +
            listOf("commit", "-m", message)
    run(repo, "commit", arguments)
  }

  override suspend fun push(repo: Path, branch: String, token: String) {
    withAskpass(token) { askpassEnv ->
      run(repo, "push origin $branch", listOf("push", "origin", branch), extraEnv = askpassEnv)
    }
  }

  override suspend fun headSha(repo: Path): String =
      run(repo, "rev-parse HEAD", listOf("rev-parse", "HEAD")).standardOutput.trim()

  private suspend fun run(
      workingDirectory: Path?,
      command: String,
      arguments: List<String>,
      extraEnv: Map<String, String> = emptyMap(),
  ): SysProcessOutcome {
    val outcome = spawn(workingDirectory, arguments, extraEnv)
    if (outcome.exitCode != 0) {
      throw GitCliException(command, outcome.exitCode, combined(outcome))
    }
    return outcome
  }

  private suspend fun spawn(
      workingDirectory: Path?,
      arguments: List<String>,
      extraEnv: Map<String, String> = emptyMap(),
  ): SysProcessOutcome =
      spawner.spawn(
          executable = gitExecutable,
          workingDirectory = workingDirectory,
          arguments = arguments,
          environment = baseEnvironment() + extraEnv,
      )

  private fun baseEnvironment(): Map<String, String> =
      System.getenv() + ("GIT_TERMINAL_PROMPT" to "0")

  private fun combined(outcome: SysProcessOutcome): String =
      (outcome.standardOutput + outcome.errorOutput).trim()

  // Runs [block] with a GIT_ASKPASS script that echoes [token] on demand, removed afterwards.
  private suspend fun <T> withAskpass(token: String, block: suspend (Map<String, String>) -> T): T {
    val script = writeAskpassScript()
    return try {
      block(
          mapOf(
              "GIT_ASKPASS" to script.toAbsolutePath().toString(),
              askpassTokenEnvVar to token,
          )
      )
    } finally {
      Files.deleteIfExists(script)
    }
  }

  private fun writeAskpassScript(): Path {
    val script = Files.createTempFile("farm-git-askpass", ".sh")
    script.toFile().writeText("#!/bin/sh\necho \"\$$askpassTokenEnvVar\"\n")
    Files.setPosixFilePermissions(
        script,
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
    )
    return script
  }

  private companion object {
    const val askpassTokenEnvVar = "FARM_GIT_ASKPASS_TOKEN"
  }
}
