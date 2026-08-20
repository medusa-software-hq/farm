package software.medusa.farm.github

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.net.http.HttpClient
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val httpOk = 200
private const val httpCreated = 201

/**
 * Signs a short-lived App JWT with the app's private key and exchanges it for the App-management
 * artifacts: an org's installation id, and a fresh access token for an installation.
 */
class GhProperAppApiClient
private constructor(
    private val clientId: String,
    private val privateKey: RSAPrivateKey,
    private val http: GhHttp,
) : GhAppApiClient {
  override suspend fun resolveInstallationId(orgLogin: GhOrgLogin): GhInstallationId {
    val response = http.get("/orgs/${orgLogin.value}/installation", bearer = createAppJwt())
    check(response.statusCode() == httpOk) {
      "GitHub installation lookup failed: ${response.statusCode()} ${response.body()}"
    }
    return GhInstallationId(gitHubJson.decodeFromString<InstallationDto>(response.body()).id)
  }

  override suspend fun fetchDeclaredPermissions(): GhAppPermissionSet {
    val response = http.get("/app", bearer = createAppJwt())
    check(response.statusCode() == httpOk) {
      "GitHub app lookup failed: ${response.statusCode()} ${response.body()}"
    }
    return gitHubJson.decodeFromString<AppDto>(response.body()).permissions.toPermissionSet()
  }

  override suspend fun mintInstallationToken(
      installationId: GhInstallationId
  ): MintedGhInstallationToken {
    val response =
        http.post(
            "/app/installations/${installationId.value}/access_tokens",
            bearer = createAppJwt(),
        )
    check(response.statusCode() == httpCreated) {
      "GitHub installation token request failed: ${response.statusCode()} ${response.body()}"
    }
    val dto = gitHubJson.decodeFromString<InstallationTokenDto>(response.body())
    return MintedGhInstallationToken(token = dto.token, expiresAt = Instant.parse(dto.expiresAt))
  }

  private fun createAppJwt(): String {
    val now = Instant.now()
    val claims =
        JWTClaimsSet.Builder()
            // GitHub accepts the app's client id as the issuer.
            .issuer(clientId)
            // Allow 60s of clock drift, and stay well under GitHub's 10-minute maximum.
            .issueTime(Date.from(now.minusSeconds(60)))
            .expirationTime(Date.from(now.plusSeconds(9 * 60)))
            .build()
    val signedJwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims)
    signedJwt.sign(RSASSASigner(privateKey))
    return signedJwt.serialize()
  }

  companion object {
    /**
     * [privateKeyPem] must be an unencrypted PKCS#8 PEM (a `BEGIN PRIVATE KEY` block); GitHub
     * issues App keys in PKCS#1, converted once out of band (see the module README). Parsing here,
     * at build time, keeps a wrong format a startup failure rather than a first-call one.
     */
    fun build(
        clientId: String,
        privateKeyPem: String,
        baseUrl: String = gitHubApiBaseUrl,
        httpClient: HttpClient = HttpClient.newHttpClient(),
    ): GhProperAppApiClient =
        GhProperAppApiClient(
            clientId,
            parsePkcs8PrivateKey(privateKeyPem),
            GhHttp(baseUrl, httpClient),
        )

    private fun parsePkcs8PrivateKey(pem: String): RSAPrivateKey {
      val base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace(Regex("\\s"), "")
      val keyBytes = Base64.getDecoder().decode(base64)
      return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
          as RSAPrivateKey
    }
  }
}

/**
 * Ids this client has no name for are dropped: a permission nothing asks for cannot be one
 * something is missing. A mode it has no name for is not dropped — that would read as the App not
 * holding a permission it does hold, and fail a farm that is fine.
 */
private fun Map<String, String>.toPermissionSet(): GhAppPermissionSet {
  val idByWireValue = GhPermissionId.entries.associateBy { it.wireValue }
  val modeByWireValue = GhPermissionMode.entries.associateBy { it.wireValue }

  return GhAppPermissionSet(
      mapNotNull { (id, mode) ->
            val permissionId = idByWireValue[id] ?: return@mapNotNull null
            val permissionMode =
                checkNotNull(modeByWireValue[mode]) {
                  "GitHub reported the $id permission as \"$mode\", which is neither read nor write"
                }
            permissionId to permissionMode
          }
          .toMap()
  )
}

@Serializable private class AppDto(val permissions: Map<String, String>)

@Serializable private class InstallationDto(val id: Long)

@Serializable
private class InstallationTokenDto(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
)
