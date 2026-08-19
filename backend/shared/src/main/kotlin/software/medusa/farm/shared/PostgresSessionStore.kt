package software.medusa.farm.shared

import java.time.OffsetDateTime
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

  override suspend fun startRunAttempt(id: String, ordinal: Int, attempt: Int) {
    withContext(Dispatchers.IO) {
      database.transaction {
        database.sessionRunQueries.startRunAttempt(
            sessionId = id,
            ordinal = ordinal,
            attempt = attempt,
        )
        database.sessionRunQueries.deleteRunAttemptEntries(
            sessionId = id,
            ordinal = ordinal,
            attempt = attempt,
        )
      }
    }
  }

  override suspend fun appendRunEntry(
      id: String,
      ordinal: Int,
      attempt: Int,
      position: Int,
      entry: AgentRunEntry,
  ) {
    withContext(Dispatchers.IO) {
      database.sessionRunQueries.appendRunEntry(
          sessionId = id,
          ordinal = ordinal,
          attempt = attempt,
          position = position,
          entry = json.encodeToString(entry),
      )
    }
  }

  override suspend fun finishRunAttempt(
      id: String,
      ordinal: Int,
      attempt: Int,
      outcome: AgentRunOutcome,
      cost: AgentRunCost?,
      summary: String,
  ) {
    withContext(Dispatchers.IO) {
      database.sessionRunQueries.finishRunAttempt(
          outcome = outcome.name,
          cost = cost?.let { json.encodeToString(it) },
          summary = summary,
          sessionId = id,
          ordinal = ordinal,
          attempt = attempt,
      )
    }
  }

  override suspend fun getRuns(id: String): List<SessionRun> =
      withContext(Dispatchers.IO) {
        val entriesByAttempt =
            database.sessionRunQueries.selectEntriesForSession(id).executeAsList().groupBy({
              it.ordinal to it.attempt
            }) {
              json.decodeFromString<AgentRunEntry>(it.entry)
            }

        database.sessionRunQueries
            .selectAttemptsForSession(id)
            .executeAsList()
            .groupBy { it.ordinal }
            .map { (ordinal, rows) ->
              val attempts = rows.mapIndexed { index, row ->
                val log = AgentRunLog(entriesByAttempt[ordinal to row.attempt].orEmpty())

                // Outcome and summary are written together when a try closes, so a row missing
                // either never closed. Rows arrive in attempt order, so anything before the
                // last one has been superseded and is not still going.
                val outcome = row.outcome
                val summary = row.summary
                when {
                  outcome == null || summary == null ->
                      if (index == rows.lastIndex) {
                        SessionRunAttempt.Running(
                            number = row.attempt,
                            log = log,
                            startedAt = row.started_at.toInstant(),
                        )
                      } else {
                        SessionRunAttempt.Abandoned(
                            number = row.attempt,
                            log = log,
                            startedAt = row.started_at.toInstant(),
                        )
                      }

                  else ->
                      SessionRunAttempt.Finished(
                          number = row.attempt,
                          log = log,
                          outcome = AgentRunOutcome.valueOf(outcome),
                          cost = row.cost?.let { json.decodeFromString(it) },
                          summary = summary,
                          startedAt = row.started_at.toInstant(),
                      )
                }
              }

              SessionRun(ordinal = ordinal, attempts = attempts)
            }
            .sortedBy { it.ordinal }
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
