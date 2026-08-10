package software.medusa.farm.worker

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class WorkerConfig(val databaseUrl: String, val tick: Duration, val maxIndex: Int) {
  companion object {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): WorkerConfig =
        WorkerConfig(
            databaseUrl = env["DATABASE_URL"] ?: error("DATABASE_URL is required"),
            tick = (env["FIB_TICK_MS"]?.toLong() ?: DEFAULT_TICK_MS).milliseconds,
            maxIndex = env["FIB_MAX_INDEX"]?.toInt() ?: DEFAULT_MAX_INDEX,
        )

    /** A config targeting [databaseUrl] with the default tick and max index. */
    fun withDefaults(databaseUrl: String): WorkerConfig =
        WorkerConfig(
            databaseUrl = databaseUrl,
            tick = DEFAULT_TICK_MS.milliseconds,
            maxIndex = DEFAULT_MAX_INDEX,
        )

    private const val DEFAULT_TICK_MS = 500L
    private const val DEFAULT_MAX_INDEX = 40
  }
}
