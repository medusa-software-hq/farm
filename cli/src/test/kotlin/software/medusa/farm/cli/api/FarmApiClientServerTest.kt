package software.medusa.farm.cli.api

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import software.medusa.farm.cli.auth.IdTokenProvider
import software.medusa.farm.github.GhAppApiClient
import software.medusa.farm.github.GhAppPermissionSet
import software.medusa.farm.github.GhInstallationId
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.MintedGhInstallationToken
import software.medusa.farm.server.GitHubOrgService
import software.medusa.farm.server.NoOpAuthDecorator
import software.medusa.farm.server.RepoSyncStarter
import software.medusa.farm.server.SyncAllStarter
import software.medusa.farm.server.buildServer
import software.medusa.farm.shared.AgentRunOutcome
import software.medusa.farm.shared.AgentStep
import software.medusa.farm.shared.AgentToolAction
import software.medusa.farm.shared.AgentWarning
import software.medusa.farm.shared.FarmStore
import software.medusa.farm.shared.InMemoryIssueStore
import software.medusa.farm.shared.InMemoryLinkedOrgStore
import software.medusa.farm.shared.InMemoryRepoStore
import software.medusa.farm.shared.InMemorySessionStore

/**
 * The CLI's questions asked of a real server, so what comes back is read the way the service sends
 * it. The rendering is covered by [software.medusa.farm.cli.command.RunLogTest]; this is about the
 * answers, and about a session's account of itself surviving the trip.
 */
class FarmApiClientServerTest {
  @Test
  fun `reads a session and what its run did`(): Unit = runBlocking {
    val store = inMemoryStore()
    store.linkedOrg.link(INSTALLATION_ID, "medusa-software-hq")
    store.session.create(
        SESSION_ID,
        INSTALLATION_ID,
        REPO_ID,
        87,
        "medusa-software-hq/farm",
        "Fix up a pull request when its checks fail",
    )
    store.session.startRunAttempt(SESSION_ID, ordinal = 0, attempt = 1)
    store.session.appendRunEntry(
        SESSION_ID,
        0,
        1,
        0,
        AgentStep("Reading the greeter.", listOf(AgentToolAction.ReadFile("Greeter.java"))),
    )
    store.session.appendRunEntry(SESSION_ID, 0, 1, 1, AgentWarning("the backend said something"))
    store.session.finishRunAttempt(
        SESSION_ID,
        0,
        1,
        AgentRunOutcome.SUCCEEDED,
        null,
        "Changed the greeting",
    )
    store.session.recordPullRequest(SESSION_ID, 110, "https://example.invalid/110", "abc123")
    store.session.awaitReview(SESSION_ID)

    val server =
        buildServer(
            originRegex = "^http://localhost:\\d+$",
            port = EPHEMERAL_PORT,
            auth = NoOpAuthDecorator,
            farmStore = store,
            gitHubOrgs =
                GitHubOrgService(UnusedAppApiClient, store.linkedOrg, UnusedRepoSyncStarter),
            syncAllStarter = UnusedSyncAllStarter,
        )
    server.start().join()

    try {
      FarmApiClient(
              ApiEndpoint("127.0.0.1", server.activeLocalPort(), useTls = false),
              UncheckedIdTokenProvider,
          )
          .use { client ->
            val session = client.listSessions().single()
            assertEquals(SESSION_ID, session.id)
            assertEquals("AWAITING_REVIEW", session.state)
            assertEquals("https://example.invalid/110", session.pullRequestUrl)

            val attempt = client.getSessionRuns(SESSION_ID).single().attempts.single()
            assertEquals("FINISHED", attempt.state)
            assertEquals("SUCCEEDED", attempt.outcome?.outcome)

            val step = assertIs<RunEntry.Step>(attempt.entries[0])
            assertEquals("Reading the greeter.", step.text)
            assertEquals(listOf(RunAction.Read("Greeter.java")), step.actions)

            // Interlaced as it happened: a warning between the steps stays between them.
            val warning = assertIs<RunEntry.Warning>(attempt.entries[1])
            assertEquals("the backend said something", warning.text)
          }
    } finally {
      server.stop().join()
    }
  }

  private fun inMemoryStore(): FarmStore {
    val clock = Clock.systemUTC()

    return FarmStore(
        InMemoryLinkedOrgStore(),
        InMemoryRepoStore(clock),
        InMemoryIssueStore(clock),
        InMemorySessionStore(clock),
    )
  }

  /** The no-op decorator asks nothing of the credential, so this stands in for one. */
  private object UncheckedIdTokenProvider : IdTokenProvider {
    override fun provideFreshIdToken(): String = "unchecked"
  }

  private object UnusedAppApiClient : GhAppApiClient {
    override suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId =
        error("reading sessions must not reach GitHub")

    override suspend fun fetchDeclaredPermissions(): GhAppPermissionSet =
        error("reading sessions must not reach GitHub")

    override suspend fun mintInstallationToken(
        installationId: GhInstallationId
    ): MintedGhInstallationToken = error("reading sessions must not reach GitHub")
  }

  private object UnusedRepoSyncStarter : RepoSyncStarter {
    override suspend fun start(installationId: Long) = error("reading sessions starts no sync")
  }

  private object UnusedSyncAllStarter : SyncAllStarter {
    override suspend fun start() = error("reading sessions starts no sweep")
  }

  private companion object {
    const val SESSION_ID = "session-under-test"
    const val INSTALLATION_ID = 1L
    const val REPO_ID = 2L

    /** Whatever is free; the client is told where it landed. */
    const val EPHEMERAL_PORT = 0
  }
}
