package software.medusa.farm.systemtest

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The GitHub a reviewer does, rather than the GitHub Farm does: filing the issue, asking for
 * changes, merging. None of it belongs on the production client, which has no business creating
 * issues or merging anything.
 */
class FixtureGitHub(
    private val repoFullName: String,
    private val token: String,
) {
  private val http = HttpClient.newHttpClient()

  private val json = Json { ignoreUnknownKeys = true }

  /** Templates copy files, not labels, so the label Farm gates on has to be made. */
  fun ensureLabel(name: String) {
    // Already there is success: a repeated run of this must not fail on the second.
    post(
        "labels",
        buildJsonObject {
          put("name", name)
          put("color", "ededed")
        },
        allow = setOf(201, 422),
    )
  }

  fun createIssue(title: String, body: String, label: String): Int {
    val response =
        post(
            "issues",
            buildJsonObject {
              put("title", title)
              put("body", body)
              put("labels", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(label)) })
            },
            allow = setOf(201),
        )

    return response["number"]!!.jsonPrimitive.int
  }

  /** The files a pull request touches, so a review can be left on one the agent actually wrote. */
  fun changedPaths(pullRequestNumber: Int): List<String> =
      getArray("pulls/$pullRequestNumber/files").map {
        it.jsonObject["filename"]!!.jsonPrimitive.content
      }

  /**
   * Asks for changes, saying something in the review's own box and something against a file. A
   * file-level comment rather than a line one: a line comment has to land inside the diff, which
   * means guessing at what the agent changed and where.
   */
  fun requestChanges(pullRequestNumber: Int, body: String, path: String, comment: String) {
    post(
        "pulls/$pullRequestNumber/reviews",
        buildJsonObject {
          put("event", "REQUEST_CHANGES")
          put("body", body)
          put(
              "comments",
              buildJsonArray {
                add(
                    buildJsonObject {
                      put("path", path)
                      put("subject_type", "file")
                      put("body", comment)
                    }
                )
              },
          )
        },
        allow = setOf(200, 201),
    )
  }

  fun merge(pullRequestNumber: Int) {
    put("pulls/$pullRequestNumber/merge", buildJsonObject { put("merge_method", "squash") })
  }

  /** The open pull requests, newest first, as (number, head sha). */
  fun openPullRequests(): List<Pair<Int, String>> =
      getArray("pulls?state=open&sort=created&direction=desc").map {
        val pr = it.jsonObject
        pr["number"]!!.jsonPrimitive.int to pr["head"]!!.jsonObject["sha"]!!.jsonPrimitive.content
      }

  private fun getArray(path: String) =
      json.parseToJsonElement(send(request(path).GET().build(), setOf(200))).jsonArray

  private fun post(path: String, body: JsonObject, allow: Set<Int>): JsonObject {
    val raw =
        send(
            request(path).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
            allow,
        )

    return if (raw.isBlank()) buildJsonObject {} else json.parseToJsonElement(raw).jsonObject
  }

  private fun put(path: String, body: JsonObject) {
    send(
        request(path).PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
        setOf(200),
    )
  }

  private fun request(path: String) =
      HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$repoFullName/$path"))
          .header("Authorization", "Bearer $token")
          .header("Accept", "application/vnd.github+json")
          .header("User-Agent", "farm-ephemeral")

  private fun send(request: HttpRequest, allow: Set<Int>): String {
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in allow) {
      "${request.method()} ${request.uri()} answered ${response.statusCode()}: ${response.body()}"
    }

    return response.body()
  }
}
