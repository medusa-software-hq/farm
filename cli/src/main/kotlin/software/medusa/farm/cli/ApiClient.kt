package software.medusa.farm.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val counterService = "/medusa.farm.v1.FarmService"

private val apiJson = Json { ignoreUnknownKeys = true }

/** The FarmService responses — all three RPCs return just the current count (proto3 JSON). */
@Serializable internal data class CountResponse(val count: Int = 0)

class ApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * Talks to FarmService over its unframed (Connect/JSON) endpoint — a plain HTTPS POST of the
 * request message as JSON, with the caller's Google ID token as a bearer credential. The server
 * enables unframed requests (see the backend's Server.kt), so no gRPC client is needed.
 */
class CounterApiClient(
    private val baseUrl: String,
    private val idTokenProvider: () -> String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
  fun getCount(): Int = count("GetCount")

  fun increment(): Int = count("Increment")

  fun decrement(): Int = count("Decrement")

  private fun count(method: String): Int =
      apiJson.decodeFromString<CountResponse>(post(method, "{}")).count

  private fun post(method: String, body: String): String {
    val request =
        HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}$counterService/$method"))
            .header("Authorization", "Bearer ${idTokenProvider()}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    val response = httpClient.send(request, BodyHandlers.ofString())
    val status = response.statusCode()
    if (status == 401 || status == 403) {
      throw ApiException(
          status,
          "The API rejected your identity (HTTP $status). Your session may have lapsed, or your " +
              "account isn't allowed — try 'ms-farm login' again.",
      )
    }
    if (status !in 200..299) {
      throw ApiException(status, "API error (HTTP $status): ${response.body().take(500)}")
    }
    return response.body()
  }
}
