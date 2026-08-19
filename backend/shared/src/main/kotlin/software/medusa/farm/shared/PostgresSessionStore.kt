package software.medusa.farm.shared

import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import software.medusa.farm.shared.db.FarmDatabase

/** [SessionStore] backed by a Postgres database. */
class PostgresSessionStore(
    private val database: FarmDatabase,
) : SessionStore {
  private val json = Json

  override suspend fun create(
      id: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
      repoFullName: String,
      title: String,
  ) {
    withContext(Dispatchers.IO) {
      database.sessionsQueries.create(
          id = id,
          installationId = installationId,
          githubRepoId = githubRepoId,
          number = number,
          repoFullName = repoFullName,
          title = title,
          state = SessionState.RUNNING.name,
      )
    }
  }

  override suspend fun complete(id: String) {
    withContext(Dispatchers.IO) {
      database.sessionsQueries.setState(state = SessionState.COMPLETED.name, id = id)
    }
  }

  override suspend fun fail(id: String) {
    withContext(Dispatchers.IO) {
      database.sessionsQueries.setState(state = SessionState.FAILED.name, id = id)
    }
  }

  override suspend fun recordPullRequest(id: String, number: Int, url: String, headSha: String) {
    withContext(Dispatchers.IO) {
      database.sessionPrQueries.recordPullRequest(
          sessionId = id,
          prNumber = number,
          prUrl = url,
          headSha = headSha,
      )
    }
  }

  override suspend fun startRun(id: String, ordinal: Int) {
    withContext(Dispatchers.IO) {
      database.transaction {
        database.sessionRunQueries.startRun(
            id = UUID.randomUUID().toString(),
            sessionId = id,
            ordinal = ordinal,
        )
        database.sessionRunQueries.deleteRunEntries(sessionId = id, ordinal = ordinal)
      }
    }
  }

  override suspend fun appendRunEntry(
      id: String,
      ordinal: Int,
      position: Int,
      entry: AgentRunEntry,
  ) {
    withContext(Dispatchers.IO) {
      database.sessionRunQueries.appendRunEntry(
          sessionId = id,
          ordinal = ordinal,
          position = position,
          entry = json.encodeToString(entry),
      )
    }
  }

  override suspend fun finishRun(
      id: String,
      ordinal: Int,
      outcome: AgentRunOutcome,
      cost: AgentRunCost?,
      summary: String,
  ) {
    withContext(Dispatchers.IO) {
      database.sessionRunQueries.finishRun(
          outcome = outcome.name,
          cost = cost?.let { json.encodeToString(it) },
          summary = summary,
          sessionId = id,
          ordinal = ordinal,
      )
    }
  }

  override suspend fun getRuns(id: String): List<SessionRun> =
      withContext(Dispatchers.IO) {
        val logsByOrdinal =
            database.sessionRunQueries.selectEntriesForSession(id).executeAsList().groupBy({
              it.ordinal
            }) {
              json.decodeFromString<AgentRunEntry>(it.entry)
            }

        database.sessionRunQueries.selectForSession(id).executeAsList().map { row ->
          val log = AgentRunLog(logsByOrdinal[row.ordinal].orEmpty())

          // Outcome and summary are written together when the run is closed, so either one being
          // present means it is over; reading both keeps a half-written row from passing as one.
          val outcome = row.outcome
          val summary = row.summary
          if (outcome == null || summary == null) {
            SessionRun.Running(
                ordinal = row.ordinal,
                log = log,
                createdAt = row.created_at.toInstant(),
            )
          } else {
            SessionRun.Finished(
                ordinal = row.ordinal,
                log = log,
                outcome = AgentRunOutcome.valueOf(outcome),
                cost = row.cost?.let { json.decodeFromString(it) },
                summary = summary,
                createdAt = row.created_at.toInstant(),
            )
          }
        }
      }

  override suspend fun listForOrgs(installationIds: List<Long>): List<Session> =
      withContext(Dispatchers.IO) {
        installationIds.flatMap { installationId ->
          database.sessionsQueries.listForOrg(installationId).executeAsList().map {
            session(
                it.id,
                it.installation_id,
                it.github_repo_id,
                it.number,
                it.repo_full_name,
                it.title,
                it.state,
                it.created_at,
                it.updated_at,
                it.pr_number,
                it.pr_url,
                it.head_sha,
                it.merged_at,
            )
          }
        }
      }

  override suspend fun get(id: String): Session? =
      withContext(Dispatchers.IO) {
        database.sessionsQueries.get(id).executeAsOneOrNull()?.let {
          session(
              it.id,
              it.installation_id,
              it.github_repo_id,
              it.number,
              it.repo_full_name,
              it.title,
              it.state,
              it.created_at,
              it.updated_at,
              it.pr_number,
              it.pr_url,
              it.head_sha,
              it.merged_at,
          )
        }
      }

  @Suppress("LongParameterList")
  private fun session(
      id: String,
      installationId: Long,
      githubRepoId: Long,
      number: Int,
      repoFullName: String,
      title: String,
      state: String,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime,
      prNumber: Int?,
      prUrl: String?,
      prHeadSha: String?,
      prMergedAt: OffsetDateTime?,
  ): Session {
    val parsed = SessionState.valueOf(state)
    return Session(
        id = id,
        installationId = installationId,
        githubRepoId = githubRepoId,
        number = number,
        repoFullName = repoFullName,
        title = title,
        state = parsed,
        startedAt = createdAt.toInstant(),
        finishedAt = if (parsed == SessionState.RUNNING) null else updatedAt.toInstant(),
        pullRequest =
            if (prNumber != null && prUrl != null && prHeadSha != null) {
              SessionPullRequest(prNumber, prUrl, prHeadSha, prMergedAt?.toInstant())
            } else {
              null
            },
    )
  }
}
