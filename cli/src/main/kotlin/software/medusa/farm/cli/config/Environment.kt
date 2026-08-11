package software.medusa.farm.cli.config

import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import java.nio.file.Path
import software.medusa.farm.cli.api.ApiEndpoint

/**
 * The closed set of environments a single CLI invocation runs against, selected **once** by the
 * `FARM_ENVIRONMENT` session property (`AWS_PROFILE`-style — deliberately no per-command flag; a
 * flag would invite mixed-environment command sequences). Absent → [Prod].
 *
 * Each environment is a self-contained bundle of everything a command needs — where its state lives
 * (partitioned, never mixed) and which backend + OAuth client to talk to — so the CLI behaves as N
 * independent instances sharing a binary. The prod/staging backend host and Desktop OAuth client id
 * are deterministic, public values taken from the resolved per-environment cache Terraform also
 * reads, generated into [GeneratedEnvironments] at build time so they can't drift from the deployed
 * environments.
 */
sealed interface Environment {
  /**
   * Short lowercase name (`prod`/`staging`/`local`); also the state-subdir name for prod/staging.
   */
  val label: String

  /**
   * This environment's private state directory beneath a resolved [baseConfigPath] — never shared.
   * Prod/staging partition by [label]; [Local] uses its own explicit path and ignores the base.
   */
  fun resolveConfigDirPath(baseConfigPath: Path): Path

  /** The one backend endpoint for this environment. */
  val apiEndpoint: ApiEndpoint

  /** The Desktop OAuth client id sign-in presents; the API's accepted CLI audience. */
  val oauthClientId: ClientID

  /**
   * The OAuth client secret for [oauthClientId] — baked at publish, or an env override for a local
   * build, else a placeholder that only satisfies the local backend (which ignores auth). For a
   * Desktop client Google explicitly does not treat this as confidential.
   */
  val oauthClientSecret: Secret

  /** The one-line stderr banner a non-prod session prints so a human can't mix environments. */
  val marker: String?

  /** The env var a local build sets to supply [oauthClientSecret] when nothing is baked. */
  val oauthClientSecretEnvVar: String

  data object Prod : Environment {
    override val label = "prod"
    override val apiEndpoint = ApiEndpoint(GeneratedEnvironments.prod.apiHost, 443, useTls = true)

    override fun resolveConfigDirPath(baseConfigPath: Path): Path = baseConfigPath.resolve(label)

    override val oauthClientId = ClientID(GeneratedEnvironments.prod.oauthClientId)
    override val oauthClientSecretEnvVar = "FARM_CLI_OAUTH_CLIENT_SECRET"
    override val oauthClientSecret: Secret
      get() =
          Secret(
              System.getenv(oauthClientSecretEnvVar)?.ifBlank { null }
                  ?: BuildConfig.bakedProperty("oauthClientSecret")
                  ?: PLACEHOLDER_CLIENT_SECRET
          )

    override val marker: String? = null
  }

  data object Staging : Environment {
    override val label = "staging"
    override val apiEndpoint =
        ApiEndpoint(GeneratedEnvironments.staging.apiHost, 443, useTls = true)

    override fun resolveConfigDirPath(baseConfigPath: Path): Path = baseConfigPath.resolve(label)

    override val oauthClientId = ClientID(GeneratedEnvironments.staging.oauthClientId)
    override val oauthClientSecretEnvVar = "FARM_CLI_OAUTH_CLIENT_SECRET_STAGING"
    override val oauthClientSecret: Secret
      get() =
          Secret(
              System.getenv(oauthClientSecretEnvVar)?.ifBlank { null }
                  ?: BuildConfig.bakedProperty("stagingOauthClientSecret")
                  ?: PLACEHOLDER_CLIENT_SECRET
          )

    override val marker = "[staging]"
  }

  /**
   * A developer's local backend. Requires an explicit config path (a temp dir in practice, keeping
   * hermetic tests parallel-safe) and port. The local backend runs the no-op auth decorator, so its
   * accepted audience is irrelevant — [oauthClientId]/[oauthClientSecret] mirror prod's only so a
   * local `login` attempt has *something* to present.
   */
  data class Local(private val configDir: Path, val port: Int) : Environment {
    companion object {
      const val LABEL = "local"
    }

    override val label = LABEL
    override val apiEndpoint = ApiEndpoint("127.0.0.1", port, useTls = false)
    override val oauthClientId = Prod.oauthClientId
    override val oauthClientSecretEnvVar = Prod.oauthClientSecretEnvVar
    override val oauthClientSecret: Secret
      get() = Prod.oauthClientSecret

    override val marker = "[local]"

    /** Local uses its caller-supplied config path directly; the shared base is irrelevant here. */
    override fun resolveConfigDirPath(baseConfigPath: Path): Path = configDir
  }

  companion object {
    const val ENV_VAR = "FARM_ENVIRONMENT"
    const val LOCAL_CONFIG_PATH_ENV = "FARM_LOCAL_CONFIG_PATH"
    const val LOCAL_PORT_ENV = "FARM_API_LOCAL_PORT"

    // Stand-in when no real secret is baked or set: fine for the local backend (which ignores
    // auth),
    // and Google simply rejects it for prod/staging — a released build always bakes the real one.
    private const val PLACEHOLDER_CLIENT_SECRET = "local-development-unset"

    /**
     * Resolve the environment for this invocation from the `FARM_ENVIRONMENT` selector (the caller
     * passes the raw env values). Matching is exact: absent → [Prod], else one of the three labels
     * verbatim; `local` requires both local variables. Anything else, or a misconfigured `local`,
     * raises [EnvironmentSelectionException] for a clean top-level message.
     */
    fun current(raw: String?, localConfigPath: String?, localPort: String?): Environment =
        when (raw) {
          null,
          Prod.label -> Prod
          Staging.label -> Staging
          Local.LABEL -> local(localConfigPath, localPort)
          else ->
              throw EnvironmentSelectionException(
                  "Unknown $ENV_VAR '$raw'. Valid values: ${Prod.label} (default), " +
                      "${Staging.label}, ${Local.LABEL}."
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
