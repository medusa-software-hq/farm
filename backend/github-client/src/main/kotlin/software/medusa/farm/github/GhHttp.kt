package software.medusa.farm.github

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json

internal const val gitHubApiBaseUrl = "https://api.github.com"

private const val acceptHeader = "application/vnd.github+json"
private const val userAgent = "medusa-farm"

internal val gitHubJson = Json { ignoreUnknownKeys = true }

/** The REST plumbing shared by every client: a bearer-authenticated call against one base URL. */
internal class GhHttp(
    private val baseUrl: String,
    private val httpClient: HttpClient,
) {
  suspend fun get(path: String, bearer: String): HttpResponse<String> =
      send(request(path, bearer).GET().build())

  suspend fun post(path: String, bearer: String): HttpResponse<String> =
      send(request(path, bearer).POST(HttpRequest.BodyPublishers.noBody()).build())

  private fun request(path: String, bearer: String): HttpRequest.Builder =
      HttpRequest.newBuilder(URI.create("$baseUrl$path"))
          .header("Authorization", "Bearer $bearer")
          .header("Accept", acceptHeader)
          .header("User-Agent", userAgent)

  private suspend fun send(request: HttpRequest): HttpResponse<String> =
      httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
}
