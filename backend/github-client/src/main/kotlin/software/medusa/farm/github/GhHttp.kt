package software.medusa.farm.github

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json

internal const val gitHubApiBaseUrl = "https://api.github.com"

private const val acceptHeader = "application/vnd.github+json"
private const val userAgent = "medusa-farm"
private const val httpOk = 200

internal val gitHubJson = Json { ignoreUnknownKeys = true }

private val nextLinkPattern = Regex("""<([^>]+)>\s*;\s*rel="next"""")

/** The REST plumbing shared by every client: a bearer-authenticated call against one base URL. */
internal class GhHttp(
    private val baseUrl: String,
    private val httpClient: HttpClient,
) {
  suspend fun get(url: String, bearer: String): HttpResponse<String> =
      send(request(url, bearer).GET().build())

  suspend fun post(url: String, bearer: String): HttpResponse<String> =
      send(request(url, bearer).POST(HttpRequest.BodyPublishers.noBody()).build())

  /**
   * Walks a paginated GitHub collection as a [Flow], following the response's `Link: …; rel="next"`
   * header (universal across endpoints, so no per-endpoint page arithmetic) until it is absent.
   * [decodePage] turns each page body into its elements — the per-endpoint shape lives there.
   *
   * Complete-or-fail: a non-200 page throws, which the flow propagates to the collector, so a
   * truncated collection never reaches a consumer.
   */
  fun <T> getPaged(
      path: String,
      tokenProvider: GhTokenProvider,
      perPage: Int = 100,
      decodePage: (body: String) -> List<T>,
  ): Flow<T> = flow {
    var url: String? = "$path?per_page=$perPage"
    while (url != null) {
      val response = get(url, bearer = tokenProvider.provideToken())
      check(response.statusCode() == httpOk) {
        "GitHub paged GET failed: ${response.statusCode()} ${response.body()}"
      }
      decodePage(response.body()).forEach { emit(it) }
      url = response.headers().firstValue("Link").map(::nextLink).orElse(null)
    }
  }

  // Resolving against the base means a relative path (the first request) and an absolute URL (the
  // Link header's fully-formed next URL) are both handled without hand-concatenation.
  private fun request(url: String, bearer: String): HttpRequest.Builder =
      HttpRequest.newBuilder(URI.create(baseUrl).resolve(url))
          .header("Authorization", "Bearer $bearer")
          .header("Accept", acceptHeader)
          .header("User-Agent", userAgent)

  private suspend fun send(request: HttpRequest): HttpResponse<String> =
      httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
}

/** The `rel="next"` URL from a GitHub `Link` header, or null when there is no next page. */
private fun nextLink(linkHeader: String): String? =
    nextLinkPattern.find(linkHeader)?.groupValues?.get(1)
