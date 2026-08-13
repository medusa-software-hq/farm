package software.medusa.farm.gitcli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

class GitCliProperTest {
  private val git = GitCliProper(SysProcessSpawner(), SysExecutableHandle.locate("git"))
  private val author = GitCliAuthor("Farm Test", "test@farm.example")

  // Local `file://` remotes need no auth, so the token is never actually presented.
  private val unusedToken = "unused-for-local-remotes"

  @Test
  fun `clones, branches, commits, reports head, and pushes`() = runBlocking {
    val remote = bareRemoteWithInitialCommit()

    val clone = Files.createTempDirectory("gitcli-clone").resolve("repo")
    git.clone(remote.toUri().toString(), clone, unusedToken)
    assertTrue(clone.resolve("README.md").exists(), "clone lacks the seeded file")
    assertEquals("hello\n", clone.resolve("README.md").readText())

    git.createBranch(clone, "feature")
    assertEquals("feature", git.currentBranch(clone))

    assertFalse(git.hasStagedChanges(clone))
    clone.resolve("new.txt").writeText("hi")
    git.stageAll(clone)
    assertTrue(git.hasStagedChanges(clone))

    git.commit(clone, "Add a file", author, signingKey = null)
    assertFalse(git.hasStagedChanges(clone))
    assertTrue(git.headSha(clone).matches(Regex("[0-9a-f]{40}")), "head is not a full sha")

    git.push(clone, "feature", unusedToken)
    assertTrue(remoteBranches(remote).contains("feature"), "remote lacks the pushed branch")
  }

  @Test
  fun `throws GitCliException on a failing git invocation`() {
    val notARepo = Files.createTempDirectory("gitcli-empty")
    val error = runCatching { runBlocking { git.headSha(notARepo) } }.exceptionOrNull()
    assertTrue(error is GitCliException, "expected GitCliException, got $error")
  }

  /** A bare repo seeded with one commit on `main`, to clone from. */
  private fun bareRemoteWithInitialCommit(): Path {
    val remote = Files.createTempDirectory("gitcli-remote").resolve("origin.git")
    runGit(null, "init", "--bare", "-b", "main", remote.toString())

    val seed = Files.createTempDirectory("gitcli-seed").resolve("seed")
    runGit(null, "clone", remote.toString(), seed.toString())
    seed.resolve("README.md").writeText("hello\n")
    runGit(seed, "add", "-A")
    runGit(
        seed,
        "-c",
        "user.name=Seed",
        "-c",
        "user.email=seed@farm.example",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-m",
        "Initial commit",
    )
    runGit(seed, "push", "origin", "main")
    return remote
  }

  private fun remoteBranches(remote: Path): List<String> =
      runGit(null, "--git-dir=$remote", "for-each-ref", "--format=%(refname:short)", "refs/heads")
          .lines()
          .map { it.trim() }
          .filter { it.isNotEmpty() }

  private fun runGit(workingDirectory: Path?, vararg args: String): String {
    val builder = ProcessBuilder(listOf("git") + args).redirectErrorStream(true)
    workingDirectory?.let { builder.directory(it.toFile()) }
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
    return output
  }
}
