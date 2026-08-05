package software.medusa.counter.cli

import java.nio.file.Path

private const val configDirName = "ms-counter"

/**
 * The base state directory, shared by every environment: `$XDG_CONFIG_HOME/ms-counter` or, when
 * that's unset, `~/.config/ms-counter` (also on macOS). Each [Environment] owns a partitioned
 * subdirectory (or, for [Environment.Local], a caller-supplied path) beneath this.
 */
internal fun configBaseDir(
    xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME"),
    userHome: String = System.getProperty("user.home"),
): Path {
  val base =
      if (!xdgConfigHome.isNullOrBlank()) Path.of(xdgConfigHome) else Path.of(userHome, ".config")
  return base.resolve(configDirName)
}

/** Raised when `COUNTER_ENVIRONMENT` (or a `local`-only variable) is set to something unusable. */
class EnvironmentSelectionException(message: String) : Exception(message)

/** [text] wrapped in ANSI dim, but only when stderr is an interactive terminal (else plain). */
internal fun dimmedForStderr(text: String): String =
    if (System.console() != null) "[2m$text[22m" else text

/**
 * The closed set of environments a single CLI invocation runs against, selected **once** by the
 * `COUNTER_ENVIRONMENT` session property (`AWS_PROFILE`-style — deliberately no per-command flag; a
 * flag would invite mixed-environment command sequences). Absent → [Prod].
 *
 * Each environment is a self-contained bundle of everything a command needs — where its state lives
 * (partitioned, never mixed) and which backend + OAuth client to talk to — so the CLI behaves as N
 * independent instances sharing a binary. The prod/staging backend URLs and Desktop OAuth client
 * ids are deterministic, public, Terraform-computed values kept in sync with `infra/common`'s
 * `environment_config` (the `api.<subdomain_label>.<domain>` host and the per-project
 * `cli_client_id`) — mirrored here as source constants.
 */
sealed interface Environment {
  /**
   * Short lowercase name (`prod`/`staging`/`local`); also the state-subdir name for prod/staging.
   */
  val label: String

  /** This environment's private state directory — never shared. */
  val configDir: Path

  /** The one backend endpoint for this environment. */
  val apiBaseUrl: String

  /** The Desktop OAuth client id sign-in presents; the API's accepted CLI audience. */
  val oauthClientId: String

  /**
   * The OAuth client secret for [oauthClientId] — baked at publish, or an env override for a local
   * build, or null if neither is present (`login` then reports how to set it). For a Desktop client
   * Google explicitly does not treat this as confidential.
   */
  val oauthClientSecret: String?

  /** The one-line stderr banner a non-prod session prints so a human can't mix environments. */
  val marker: String?

  /** The env var a local build sets to supply [oauthClientSecret] when nothing is baked. */
  val oauthClientSecretEnvVar: String

  data object Prod : Environment {
    override val label = "prod"
    override val configDir: Path = configBaseDir().resolve(label)
    override val apiBaseUrl = "https://api.counter-baseline.medusa.software"
    override val oauthClientId =
        "390879863874-2lni09664lo24g44kakjceu2j7s164nr.apps.googleusercontent.com"
    override val oauthClientSecretEnvVar = "COUNTER_CLI_OAUTH_CLIENT_SECRET"
    override val oauthClientSecret: String?
      get() =
          System.getenv(oauthClientSecretEnvVar)?.ifBlank { null }
              ?: BuildConfig.bakedProperty("oauthClientSecret")

    override val marker: String? = null
  }

  data object Staging : Environment {
    override val label = "staging"
    override val configDir: Path = configBaseDir().resolve(label)
    override val apiBaseUrl = "https://api.counter-baseline-staging.medusa.software"
    override val oauthClientId =
        "1099281545285-smp4hh6b1rec63qgblp6apgbe534kpdd.apps.googleusercontent.com"
    override val oauthClientSecretEnvVar = "COUNTER_CLI_OAUTH_CLIENT_SECRET_STAGING"
    override val oauthClientSecret: String?
      get() =
          System.getenv(oauthClientSecretEnvVar)?.ifBlank { null }
              ?: BuildConfig.bakedProperty("stagingOauthClientSecret")

    override val marker = "[staging]"
  }

  /**
   * A developer's local backend. Requires an explicit config path (a temp dir in practice, keeping
   * hermetic tests parallel-safe) and port. The local backend runs the no-op auth decorator, so its
   * accepted audience is irrelevant — [oauthClientId]/[oauthClientSecret] mirror prod's only so a
   * local `login` attempt has *something* to present.
   */
  data class Local(override val configDir: Path, val port: Int) : Environment {
    override val label = "local"
    override val apiBaseUrl = "http://127.0.0.1:$port"
    override val oauthClientId = Prod.oauthClientId
    override val oauthClientSecretEnvVar = Prod.oauthClientSecretEnvVar
    override val oauthClientSecret: String?
      get() = Prod.oauthClientSecret

    override val marker = "[local]"
  }

  companion object {
    const val ENV_VAR = "COUNTER_ENVIRONMENT"
    const val LOCAL_CONFIG_PATH_ENV = "COUNTER_LOCAL_CONFIG_PATH"
    const val LOCAL_PORT_ENV = "COUNTER_API_LOCAL_PORT"

    /**
     * Resolve the environment for this invocation — the **single** read of `COUNTER_ENVIRONMENT` in
     * the whole CLI (the composition root calls this once and injects the result via the Clikt
     * context). Absent/blank → [Prod]; `local` requires both local variables. An unknown value or a
     * misconfigured `local` raises [EnvironmentSelectionException] for a clean top-level message.
     */
    fun current(
        raw: String? = System.getenv(ENV_VAR),
        localConfigPath: String? = System.getenv(LOCAL_CONFIG_PATH_ENV),
        localPort: String? = System.getenv(LOCAL_PORT_ENV),
    ): Environment =
        when (raw?.trim()?.lowercase()?.ifBlank { null }) {
          null,
          "prod",
          "production" -> Prod
          "staging" -> Staging
          "local" -> local(localConfigPath, localPort)
          else ->
              throw EnvironmentSelectionException(
                  "Unknown $ENV_VAR '$raw'. Valid values: prod (default), staging, local."
              )
        }

    private fun local(localConfigPath: String?, localPort: String?): Local {
      val path =
          localConfigPath?.ifBlank { null }
              ?: throw EnvironmentSelectionException(
                  "$ENV_VAR=local requires $LOCAL_CONFIG_PATH_ENV to be set to a config directory."
              )
      val portText =
          localPort?.ifBlank { null }
              ?: throw EnvironmentSelectionException(
                  "$ENV_VAR=local requires $LOCAL_PORT_ENV to be set to the local backend's port."
              )
      val port =
          portText.toIntOrNull()?.takeIf { it in 1..65535 }
              ?: throw EnvironmentSelectionException(
                  "$LOCAL_PORT_ENV must be a port number (1–65535), got '$portText'."
              )
      return Local(Path.of(path), port)
    }
  }
}
