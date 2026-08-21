package software.medusa.farm.github

import java.net.http.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val httpOk = 200

/** The GraphQL plumbing shared by every query: one bearer-authenticated POST to the endpoint. */
internal class GhGraphQl(
    baseUrl: String,
    httpClient: HttpClient,
) {
  private val http = GhHttp(baseUrl, httpClient)

  /**
   * Runs [document] with [variables] and returns its `data`.
   *
   * GraphQL answers a query it could not resolve inside the body of a 200, so a partial answer is
   * made to throw here rather than reaching a caller that only looked at the status.
   */
  suspend fun query(
      document: String,
      variables: JsonObject,
      tokenProvider: GhTokenProvider,
  ): JsonObject {
    val request = buildJsonObject {
      put("query", document)
      put("variables", variables)
    }
    val response =
        http.post("/graphql", bearer = tokenProvider.provideToken(), body = request.toString())
    if (response.statusCode() != httpOk) {
      throw GhRequestFailed(
          statusCode = response.statusCode(),
          body = response.body(),
          rateLimited = GhRequestFailed.rateLimitedBy(response.headers().map()),
          message = "GitHub GraphQL query failed: ${response.statusCode()} ${response.body()}",
      )
    }

    val envelope = gitHubJson.decodeFromString<GraphQlEnvelopeDto>(response.body())
    check(envelope.errors.isEmpty()) {
      "GitHub GraphQL query failed: ${envelope.errors.joinToString { it.message }}"
    }
    return checkNotNull(envelope.data) { "GitHub GraphQL query returned no data" }
  }
}

@Serializable
private class GraphQlEnvelopeDto(
    val data: JsonObject? = null,
    val errors: List<GraphQlErrorDto> = emptyList(),
)

@Serializable private class GraphQlErrorDto(val message: String)
