package software.medusa.farm.cli.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlin.time.Duration.Companion.seconds
import software.medusa.farm.cli.api.FarmApiClient

class RunsCommand : ManagementCommand(name = "runs") {
  override fun help(context: Context) =
      "Show what a session's runs did — every step, and how each try ended."

  private val sessionId by argument(name = "session-id", help = "As listed by 'ms-farm sessions'.")

  private val follow by
      option("-f", "--follow", help = "Keep showing what happens until the session ends.").flag()

  override fun run(apiClient: FarmApiClient) {
    val log = RunLog { echo(it) }

    if (!follow) {
      log.show(apiClient.getSessionRuns(sessionId))
      return
    }

    // Asked for again and again rather than streamed, because the service answers questions rather
    // than holding a connection open.
    //
    // The state is read before the runs, not after: a session still live when asked may have ended
    // by the time the runs come back, and the next look catches that. Read the other way round, a
    // session that ended between the two answers would be left with whatever it did in between
    // never shown, because nothing would ask again.
    while (true) {
      val session = apiClient.getSession(sessionId)
      log.show(apiClient.getSessionRuns(sessionId))

      if (!SessionState.isLive(session.state)) {
        echo("session ${SessionState.describe(session.state)}")
        return
      }

      Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
    }
  }

  private companion object {
    // Long enough not to badger the service, short enough that a run reads as it happens.
    val POLL_INTERVAL = 3.seconds
  }
}
