package software.medusa.farm.server

import io.grpc.Status
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.shared.IssueStore
import software.medusa.farm.shared.LinkedOrgStore
import software.medusa.farm.shared.RepoStore
import software.medusa.farm.shared.Session
import software.medusa.farm.shared.SessionStore
import software.medusa.farm.v1.FarmServiceGrpcKt
import software.medusa.farm.v1.GetSessionRequest
import software.medusa.farm.v1.GetSessionResponse
import software.medusa.farm.v1.Issue as IssueProto
import software.medusa.farm.v1.LinkOrgRequest
import software.medusa.farm.v1.LinkOrgResponse
import software.medusa.farm.v1.LinkedOrg as LinkedOrgProto
import software.medusa.farm.v1.ListIssuesRequest
import software.medusa.farm.v1.ListIssuesResponse
import software.medusa.farm.v1.ListLinkedOrgsRequest
import software.medusa.farm.v1.ListLinkedOrgsResponse
import software.medusa.farm.v1.ListRepositoriesRequest
import software.medusa.farm.v1.ListRepositoriesResponse
import software.medusa.farm.v1.ListSessionsRequest
import software.medusa.farm.v1.ListSessionsResponse
import software.medusa.farm.v1.Repository
import software.medusa.farm.v1.Session as SessionProto
import software.medusa.farm.v1.SyncRepositoriesRequest
import software.medusa.farm.v1.SyncRepositoriesResponse

class FarmServiceImpl(
    private val linkedOrgStore: LinkedOrgStore,
    private val repoStore: RepoStore,
    private val issueStore: IssueStore,
    private val sessionStore: SessionStore,
    private val gitHubOrgs: GitHubOrgService,
    private val syncAllStarter: SyncAllStarter,
) : FarmServiceGrpcKt.FarmServiceCoroutineImplBase() {
  // Links an org to the Farm app: resolve and store its installation, kick off a background repo
  // sync, then report the repos already known for it (empty until the first sync lands).
  override suspend fun linkOrg(request: LinkOrgRequest): LinkOrgResponse {
    val installationId = gitHubOrgs.linkOrg(GhOrgLogin(request.orgLogin))
    val repositories = repoStore.listActive(installationId.value)
    return LinkOrgResponse.newBuilder()
        .setInstallationId(installationId.value)
        .addAllRepositories(repositories.map { it.fullName })
        .build()
  }

  // The link state itself: which orgs are linked and under which installation. Surfaced on its own
  // so it reads as linked even before the first repo sync has populated the repos table.
  override suspend fun listLinkedOrgs(request: ListLinkedOrgsRequest): ListLinkedOrgsResponse =
      ListLinkedOrgsResponse.newBuilder()
          .addAllOrgs(
              linkedOrgStore.list().map { org ->
                LinkedOrgProto.newBuilder()
                    .setOrgLogin(org.orgLogin)
                    .setInstallationId(org.installationId)
                    .build()
              }
          )
          .build()

  // Steady-state read straight from the synced repos table: active repos across every linked org,
  // no live GitHub call.
  override suspend fun listRepositories(
      request: ListRepositoriesRequest
  ): ListRepositoriesResponse =
      ListRepositoriesResponse.newBuilder()
          .addAllRepositories(
              linkedOrgStore.list().flatMap { org ->
                repoStore.listActive(org.installationId).map { repo ->
                  Repository.newBuilder()
                      .setOrgLogin(org.orgLogin)
                      .setFullName(repo.fullName)
                      .build()
                }
              }
          )
          .build()

  // Steady-state read from the synced issues table: active (open) issues across every linked org,
  // no live GitHub call. Each row carries its repo's full name, so no repo join is needed. Each
  // issue is tagged with its latest processing session's state, if any.
  override suspend fun listIssues(request: ListIssuesRequest): ListIssuesResponse {
    val installationIds = linkedOrgStore.list().map { it.installationId }
    // Sessions come back ordered oldest-first, so associate keeps the latest per issue.
    val stateByIssue =
        sessionStore.listForOrgs(installationIds).associate {
          (it.githubRepoId to it.number) to it.state
        }
    return ListIssuesResponse.newBuilder()
        .addAllIssues(
            issueStore.listActiveForOrgs(installationIds).map { issue ->
              val builder =
                  IssueProto.newBuilder()
                      .setRepoFullName(issue.repoFullName)
                      .setNumber(issue.number)
                      .setTitle(issue.title)
              stateByIssue[issue.githubRepoId to issue.number]?.let {
                builder.setSessionState(it.name)
              }
              builder.build()
            }
        )
        .build()
  }

  // The agent sessions across every linked org, newest first. Self-describing rows, so no join to
  // the (possibly since-closed) issue is needed.
  override suspend fun listSessions(request: ListSessionsRequest): ListSessionsResponse {
    val installationIds = linkedOrgStore.list().map { it.installationId }
    val sessions = sessionStore.listForOrgs(installationIds).sortedByDescending { it.startedAt }
    return ListSessionsResponse.newBuilder().addAllSessions(sessions.map { it.toProto() }).build()
  }

  override suspend fun getSession(request: GetSessionRequest): GetSessionResponse {
    val session =
        sessionStore.get(request.id)
            ?: throw Status.NOT_FOUND.withDescription("No session ${request.id}")
                .asRuntimeException()
    return GetSessionResponse.newBuilder().setSession(session.toProto()).build()
  }

  private fun Session.toProto(): SessionProto =
      SessionProto.newBuilder()
          .setId(id)
          .setRepoFullName(repoFullName)
          .setNumber(number)
          .setTitle(title)
          .setState(state.name)
          .setStartedAtMillis(startedAt.toEpochMilli())
          .setFinishedAtMillis(finishedAt?.toEpochMilli() ?: 0L)
          .setPullRequestUrl(pullRequest?.url ?: "")
          .build()

  // Kicks the all-orgs sweep on demand — the same workflow the hourly schedule runs. The starter
  // does its blocking Temporal work off the request thread; an unreachable Temporal surfaces as a
  // gRPC error so the caller knows the sync did not start (unlike the best-effort on-link trigger).
  // Returns as soon as the sweep is started; the repos land as the worker processes it.
  override suspend fun syncRepositories(
      request: SyncRepositoriesRequest
  ): SyncRepositoriesResponse {
    syncAllStarter.start()
    return SyncRepositoriesResponse.getDefaultInstance()
  }
}
