package software.medusa.farm.cli.api

import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.TlsChannelCredentials
import java.util.concurrent.TimeUnit
import software.medusa.farm.cli.auth.IdTokenProvider
import software.medusa.farm.v1.AgentRunEntry
import software.medusa.farm.v1.AgentToolAction
import software.medusa.farm.v1.FarmServiceGrpc
import software.medusa.farm.v1.FarmServiceGrpc.FarmServiceBlockingStub
import software.medusa.farm.v1.GetSessionRequest
import software.medusa.farm.v1.GetSessionRunsRequest
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.ListIssuesRequest
import software.medusa.farm.v1.ListLinkedOrgsRequest
import software.medusa.farm.v1.ListSessionsRequest
import software.medusa.farm.v1.SessionRunAttempt
import software.medusa.farm.v1.SyncRepositoriesRequest

/**
 * Talks to FarmService over gRPC. A [BearerTokenInterceptor] — attached to the stub once — puts the
 * caller's Google ID token on every call, fetched (and silently refreshed) via [idTokenProvider] as
 * the call starts, so a lapsed session surfaces as NotLoggedInException rather than a gRPC error.
 * [close] shuts the channel down — the CLI uses one client per command.
 */
class FarmApiClient(endpoint: ApiEndpoint, idTokenProvider: IdTokenProvider) : AutoCloseable {
  private val channel: ManagedChannel = channelFor(endpoint)
  private val stub: FarmServiceBlockingStub =
      FarmServiceGrpc.newBlockingStub(channel)
          .withInterceptors(BearerTokenInterceptor(idTokenProvider))

  fun linkOrg(orgLogin: String): LinkOrgResult = call {
    val response = stub.linkOrg(LinkOrgRequest.newBuilder().setOrgLogin(orgLogin).build())
    LinkOrgResult(response.installationId, response.repositoriesList)
  }

  fun listLinkedOrgs(): List<LinkedOrgResult> = call {
    val response = stub.listLinkedOrgs(ListLinkedOrgsRequest.getDefaultInstance())
    response.orgsList.map { LinkedOrgResult(it.orgLogin, it.installationId) }
  }

  fun listIssues(): List<IssueResult> = call {
    val response = stub.listIssues(ListIssuesRequest.getDefaultInstance())
    response.issuesList.map { IssueResult(it.repoFullName, it.number, it.title, it.sessionState) }
  }

  fun listSessions(): List<SessionResult> = call {
    val response = stub.listSessions(ListSessionsRequest.getDefaultInstance())
    response.sessionsList.map {
      SessionResult(
          id = it.id,
          repoFullName = it.repoFullName,
          number = it.number,
          title = it.title,
          state = it.state,
          pullRequestUrl = it.pullRequestUrl,
      )
    }
  }

  fun getSession(sessionId: String): SessionResult = call {
    val session = stub.getSession(GetSessionRequest.newBuilder().setId(sessionId).build()).session
    SessionResult(
        id = session.id,
        repoFullName = session.repoFullName,
        number = session.number,
        title = session.title,
        state = session.state,
        pullRequestUrl = session.pullRequestUrl,
    )
  }

  fun getSessionRuns(sessionId: String): List<RunResult> = call {
    val response =
        stub.getSessionRuns(GetSessionRunsRequest.newBuilder().setSessionId(sessionId).build())
    response.runsList.map { run ->
      RunResult(ordinal = run.ordinal, attempts = run.attemptsList.map { it.toResult() })
    }
  }

  fun syncRepositories() = call {
    stub.syncRepositories(SyncRepositoriesRequest.getDefaultInstance())
    Unit
  }

  private fun SessionRunAttempt.toResult(): RunAttemptResult =
      RunAttemptResult(
          number = number,
          state = state,
          entries = entriesList.map { it.toResult() },
          // The outcome field is set only once the try has finished; unset reads as a default
          // message, which is not an outcome anybody should be shown.
          outcome =
              if (!hasOutcome()) null
              else
                  RunOutcomeResult(
                      outcome = outcome.outcome,
                      summary = outcome.summary,
                      usd = if (outcome.hasCost()) outcome.cost.usd else null,
                  ),
      )

  private fun AgentRunEntry.toResult(): RunEntry =
      when (entryCase) {
        AgentRunEntry.EntryCase.WARNING -> RunEntry.Warning(warning)
        else ->
            RunEntry.Step(text = step.text, actions = step.toolActionsList.map { it.toResult() })
      }

  private fun AgentToolAction.toResult(): RunAction =
      when (actionCase) {
        AgentToolAction.ActionCase.EDITED_PATH -> RunAction.Edited(editedPath)
        AgentToolAction.ActionCase.READ_PATH -> RunAction.Read(readPath)
        AgentToolAction.ActionCase.COMMAND -> RunAction.Ran(command)
        AgentToolAction.ActionCase.QUERY -> RunAction.Searched(query)
        AgentToolAction.ActionCase.OTHER_TOOL -> RunAction.Used(otherTool)
        else -> RunAction.Unknown
      }

  private inline fun <T> call(block: () -> T): T =
      try {
        block()
      } catch (e: StatusRuntimeException) {
        throw asApiException(e)
      }

  override fun close() {
    channel.shutdownNow()
    channel.awaitTermination(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)
  }

  companion object {
    private const val SHUTDOWN_TIMEOUT_SEC = 5L

    private fun channelFor(endpoint: ApiEndpoint): ManagedChannel {
      val credentials =
          if (endpoint.useTls) TlsChannelCredentials.create()
          else InsecureChannelCredentials.create()
      return Grpc.newChannelBuilderForAddress(endpoint.host, endpoint.port, credentials).build()
    }

    private fun asApiException(e: StatusRuntimeException): ApiException =
        when (e.status.code) {
          Status.Code.UNAUTHENTICATED,
          Status.Code.PERMISSION_DENIED ->
              ApiException(
                  "The API rejected your identity (${e.status.code}). Your session may have lapsed, " +
                      "or your account isn't allowed — try 'ms-farm login' again."
              )
          else -> ApiException("API error (${e.status.code}): ${e.status.description ?: e.message}")
        }
  }
}
